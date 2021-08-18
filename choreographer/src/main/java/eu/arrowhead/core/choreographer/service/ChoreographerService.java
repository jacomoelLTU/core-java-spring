/********************************************************************************
 * Copyright (c) 2020 AITIA
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   AITIA - implementation
 *   Arrowhead Consortia - conceptualization
 ********************************************************************************/

package eu.arrowhead.core.choreographer.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import eu.arrowhead.common.CommonConstants;
import eu.arrowhead.common.CoreDefaults;
import eu.arrowhead.common.CoreSystemRegistrationProperties;
import eu.arrowhead.common.Defaults;
import eu.arrowhead.common.Utilities;
import eu.arrowhead.common.database.entity.ChoreographerAction;
import eu.arrowhead.common.database.entity.ChoreographerExecutor;
import eu.arrowhead.common.database.entity.ChoreographerPlan;
import eu.arrowhead.common.database.entity.ChoreographerSession;
import eu.arrowhead.common.database.entity.ChoreographerSessionStep;
import eu.arrowhead.common.database.entity.ChoreographerStep;
import eu.arrowhead.common.dto.internal.ChoreographerSessionStatus;
import eu.arrowhead.common.dto.internal.ChoreographerSessionStepStatus;
import eu.arrowhead.common.dto.internal.ChoreographerStartSessionDTO;
import eu.arrowhead.common.dto.internal.DTOConverter;
import eu.arrowhead.common.dto.internal.TokenDataDTO;
import eu.arrowhead.common.dto.internal.TokenGenerationDetailedResponseDTO;
import eu.arrowhead.common.dto.internal.TokenGenerationProviderDTO;
import eu.arrowhead.common.dto.internal.TokenGenerationRequestDTO;
import eu.arrowhead.common.dto.shared.ChoreographerAbortStepRequestDTO;
import eu.arrowhead.common.dto.shared.ChoreographerExecuteStepRequestDTO;
import eu.arrowhead.common.dto.shared.ChoreographerExecutedStepResultDTO;
import eu.arrowhead.common.dto.shared.ChoreographerNotificationDTO;
import eu.arrowhead.common.dto.shared.OrchestrationFlags;
import eu.arrowhead.common.dto.shared.OrchestrationFormRequestDTO;
import eu.arrowhead.common.dto.shared.OrchestrationResponseDTO;
import eu.arrowhead.common.dto.shared.OrchestrationResultDTO;
import eu.arrowhead.common.dto.shared.ServiceQueryFormDTO;
import eu.arrowhead.common.dto.shared.SystemRequestDTO;
import eu.arrowhead.core.choreographer.database.service.ChoreographerPlanDBService;
import eu.arrowhead.core.choreographer.database.service.ChoreographerSessionDBService;
import eu.arrowhead.core.choreographer.exception.ChoreographerSessionException;
import eu.arrowhead.core.choreographer.executor.ExecutorData;
import eu.arrowhead.core.choreographer.executor.ExecutorSelector;

@Service
public class ChoreographerService {

    //=================================================================================================
    // members
	
	public static final String START_SESSION_DESTINATION = "start-session";
	public static final String SESSION_STEP_DONE_DESTINATION = "session-step-done";
	
	private static final String START_SESSION_MSG = "Plan execution started.";
	private static final String ABORT_SESSION_MSG = "Plan execution aborted.";
	
	@Autowired
    private ChoreographerPlanDBService planDBService;
    
    @Autowired
    private ChoreographerSessionDBService sessionDBService;

    @Autowired
    private ChoreographerDriver driver;
    
    @Autowired
    private SessionDataStorage sessionDataStorage;
    
    @Autowired
    private ExecutorSelector executorSelector;

    @Autowired
    protected CoreSystemRegistrationProperties registrationProperties;
    
    @Resource(name = CommonConstants.ARROWHEAD_CONTEXT)
    private Map<String,Object> arrowheadContext;

    private SystemRequestDTO requesterSystem;
    
    private final Logger logger = LogManager.getLogger(ChoreographerService.class);

    //=================================================================================================
    // methods

    @PostConstruct
    public void init() {
        requesterSystem = new SystemRequestDTO();
        requesterSystem.setSystemName(registrationProperties.getCoreSystemName().toLowerCase());
        requesterSystem.setAddress(registrationProperties.getCoreSystemDomainName());
        requesterSystem.setPort(registrationProperties.getCoreSystemDomainPort());
    }

    //-------------------------------------------------------------------------------------------------
    @JmsListener(destination = START_SESSION_DESTINATION)
    public void receiveStartSessionMessage(final ChoreographerStartSessionDTO startSessionDTO) {
    	logger.debug("receiveStartSessionMessage started...");
    	Assert.notNull(startSessionDTO, "Payload is null.");

        final long sessionId = startSessionDTO.getSessionId();

        try {
	        final ChoreographerPlan plan = planDBService.getPlanById(startSessionDTO.getPlanId());
	        sessionDBService.worklog(plan.getName(), sessionId, START_SESSION_MSG, null);
	        final ChoreographerSession session = sessionDBService.changeSessionStatus(sessionId, ChoreographerSessionStatus.RUNNING, null);
	        sendNotification(session, START_SESSION_MSG, null);
	        
	        selectExecutorsForPlan(sessionId, plan);
	        
	        final ChoreographerAction firstAction = plan.getFirstAction();
	        final Set<ChoreographerStep> firstSteps = new HashSet<>(planDBService.getFirstSteps(firstAction));
	
	        firstSteps.parallelStream().forEach(step -> {
	            try {
	                executeStep(step, sessionId);
	            } catch (final ChoreographerSessionException ex) {
	            	logger.debug(ex);
	            	throw ex;
	            } catch (final Throwable t) {
	            	logger.debug(t);
	            	throw new ChoreographerSessionException(sessionId, t);
	            }
	        });
        } catch (final ChoreographerSessionException ex) {
        	throw ex;
        } catch (final Throwable t) {
        	throw new ChoreographerSessionException(sessionId, t);
        }
    }

	//-------------------------------------------------------------------------------------------------
    @JmsListener(destination = SESSION_STEP_DONE_DESTINATION)
    public void receiveSessionStepDoneMessage(final ChoreographerExecutedStepResultDTO payload) {
    	logger.debug("receiveSessionStepDoneMessage started...");
    	
    	validatePayload(payload);
    	
    	switch (payload.getStatus()) {
    	case SUCCESS: handleSessionStepSuccess(payload); 
    				  break;
    	case ABORTED: handleSessionStepAborted(payload);
    				  break;
    	case ERROR:
    	case FATAL_ERROR: handleSessionStepError(payload);
    					  break;
    	default:
    		throw new IllegalArgumentException("Invalid status: " + payload.getStatus());
    	}
    	

    }
    
    //-------------------------------------------------------------------------------------------------
	public void abortSession(final long sessionId, final Long sessionStepId, final String message) {
		logger.debug("abortSession started...");
		
		final ChoreographerSession session = sessionDBService.getSessionById(sessionId);
		final List<ChoreographerSessionStep> activeSteps = sessionDBService.abortSession(sessionId, message);

		for (final ChoreographerSessionStep sessionStep : activeSteps) {
			if (sessionStepId != null && sessionStepId.longValue() == sessionStep.getId()) {
				// this step causes the abort so there is no need to abort its executor
				continue;
			}
			
			final ChoreographerExecutor executor = sessionStep.getExecutor();
			final ChoreographerAbortStepRequestDTO payload = new ChoreographerAbortStepRequestDTO(sessionId, sessionStep.getId());
			try {
				driver.abortExecutor(executor.getAddress(), executor.getPort(), executor.getBaseUri(), payload);
			} catch (final Exception ex) {
				logger.warn("Unable to send abort message - " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
				logger.debug(ex);
				final ChoreographerStep step = sessionStep.getStep();
				sessionDBService.worklog(session.getPlan().getName(), step.getAction().getName(), step.getName(), sessionId, "Unable to send abort message to the executor", ex.getMessage());
			}
		}
		
		sessionDataStorage.remove(sessionId);
		sessionDBService.worklog(session.getPlan().getName(), sessionId, "Session is aborted", null);
		sendNotification(session, ABORT_SESSION_MSG, message);
	}

	//=================================================================================================
    // assistant methods

	//-------------------------------------------------------------------------------------------------
    private void sendNotification(final ChoreographerSession session, final String message, final String details) {
    	logger.debug("sendNotification started..."); 
    	
    	try {
    		if (!Utilities.isEmpty(session.getNotifyUri())) {
    			final ChoreographerNotificationDTO payload = new ChoreographerNotificationDTO(session.getId(),
    																						  session.getPlan().getId(),
    																						  session.getPlan().getName(),
    																						  Utilities.convertZonedDateTimeToUTCString(ZonedDateTime.now()),
    																						  session.getStatus(),
    																						  message,
    																						  details);
    			driver.sendSessionNotification(session.getNotifyUri(), payload);
    		}
    	} catch (final Exception ex) {
    		// any problem in notification sending should not affect the plan execution
    		logger.warn("Unable to send notification - " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
    		logger.debug(ex);
    	}
	}
    
	//-------------------------------------------------------------------------------------------------
	private void selectExecutorsForPlan(final long sessionId, final ChoreographerPlan plan) {
		logger.debug("selectExecutorsForPlan started...");
		
		final List<ChoreographerStep> steps = planDBService.collectStepsFromPlan(plan);
		final SessionExecutorCache cache = new SessionExecutorCache();
		sessionDataStorage.put(sessionId, cache);
		
		for (final ChoreographerStep step : steps) {
			ExecutorData executorData = cache.get(step.getServiceDefinition(), step.getMinVersion(), step.getMaxVersion());
			if (executorData == null) {
				executorData = executorSelector.selectAndInit(sessionId, step, cache.getExclusions(), true);
				if (executorData == null) { // means we can't execute at least one of the steps currently 
					throw new ChoreographerSessionException(sessionId, "Can't find properly working executor for step: " + createFullyQualifiedStepName(step));
				}
				cache.put(step.getServiceDefinition(), step.getMinVersion(), step.getMaxVersion(), executorData);
			}
		}
		sessionDBService.worklog(plan.getName(), sessionId, "Found executor to all steps.", null);
	}
	
	//-------------------------------------------------------------------------------------------------
	private String createFullyQualifiedStepName(final ChoreographerStep step) {
		logger.debug("createFullyQualifiedStepName started...");
		
		return step.getAction().getPlan().getName() + CommonConstants.DOT + step.getAction().getName() + CommonConstants.DOT + step.getName();
	}
	
	//-------------------------------------------------------------------------------------------------
    private void executeStep(final ChoreographerStep step, final long sessionId) {
    	logger.debug("executeStep started...");
    	logger.debug("Execution of step with the id of " + step.getId() + " and sessionId of " + sessionId + " started.");
	
    	final String fullStepName = createFullyQualifiedStepName(step);
		final ChoreographerSessionStep sessionStep = sessionDBService.changeSessionStepStatus(sessionId, step, ChoreographerSessionStepStatus.RUNNING, "Running step: " + fullStepName);
		OrchestrationResponseDTO mainOrchestrationResponseDTO = null;
		try {
			mainOrchestrationResponseDTO = driver.queryOrchestrator(createOrchestrationFormRequestFromServiceQueryForm(Utilities.fromJson(step.getSrTemplate(), ServiceQueryFormDTO.class)));
			if (mainOrchestrationResponseDTO.getResponse().isEmpty()) { // no providers for the step
				throw new ChoreographerSessionException(sessionId, sessionStep.getId(), "No providers found for step " + fullStepName);
			}
		} catch (final Exception ex) { // problem during orchestration
			throw new ChoreographerSessionException(sessionId, sessionStep.getId(), "Problem occured while orchestration for step " + fullStepName, ex);
		}		

		final SessionExecutorCache cache = sessionDataStorage.get(sessionId);
		final ExecutorData executorData = cache.get(step.getServiceDefinition(), step.getMinVersion(), step.getMaxVersion());
		final ChoreographerExecutor executor = executorData.getExecutor();		
		final List<OrchestrationResultDTO> executorPreconditions = getOrchestrationResultsForExecutorPreconditions(step, sessionStep);
		
		final List<OrchestrationResultDTO> managedTokenList = new ArrayList<>(mainOrchestrationResponseDTO.getResponse());
		managedTokenList.addAll(executorPreconditions);
		regenerateTokensIfAny(sessionId, sessionStep.getId(), fullStepName, executor, managedTokenList); // TODO won't work in intercloud communication (only local Auth can sign the token)
		
		final ChoreographerExecuteStepRequestDTO payload = new ChoreographerExecuteStepRequestDTO(sessionId,
																								  sessionStep.getId(),
																								  executorPreconditions,
																								  mainOrchestrationResponseDTO.getResponse().get(0),
																								  step.getQuantity(),
																								  Utilities.text2Map(step.getStaticParameters()));
		
		driver.startExecutor(executor.getAddress(), executor.getPort(), executor.getBaseUri(), payload);
    }
    
    //-------------------------------------------------------------------------------------------------
	private List<OrchestrationResultDTO> getOrchestrationResultsForExecutorPreconditions(final ChoreographerStep step, final ChoreographerSessionStep sessionStep) {
    	logger.debug("getOrchestrationResultsForExecutorPreconditions started...");
    	
    	final List<OrchestrationResultDTO> result = new ArrayList<>();
    	final SessionExecutorCache cache = sessionDataStorage.get(sessionStep.getSession().getId());
    	final String serviceDefinition = step.getServiceDefinition();
		final int minVersion = step.getMinVersion() == null ? Defaults.DEFAULT_VERSION : step.getMinVersion(); 
		final int maxVersion = step.getMaxVersion() == null ? Integer.MAX_VALUE : step.getMaxVersion();
    	
		ExecutorData executorData = cache.get(serviceDefinition, minVersion, maxVersion);
    	while (executorData != null) {
    		try {
    			for (final ServiceQueryFormDTO form : executorData.getDependencyForms()) {
					final OrchestrationFormRequestDTO orchestrationForm = createOrchestrationFormRequestFromServiceQueryForm(form);
					final OrchestrationResponseDTO response = driver.queryOrchestrator(orchestrationForm);
					if (response.getResponse().isEmpty()) {
						// no provider for a dependency
						
		        		cache.remove(serviceDefinition, minVersion, maxVersion);
		        		cache.getExclusions().add(executorData.getExecutor().getId());
		        		break;
					}
					result.add(response.getResponse().get(0));
				}
    			
    			if (result.size() == executorData.getDependencyForms().size()) {
    				return result;
    			}
    		} catch (final Exception ex) {
    	   		logger.warn("Unable to orchestrate precondition service for an executor - " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        		logger.debug(ex);
        		
        		cache.remove(serviceDefinition, minVersion, maxVersion);
        		cache.getExclusions().add(executorData.getExecutor().getId());
    		}
    		
    		executorData = executorSelector.selectAndInit(sessionStep.getSession().getId(), step, cache.getExclusions(), false);
    		cache.put(serviceDefinition, minVersion, maxVersion, executorData);
    		sessionDBService.changeSessionStepExecutor(sessionStep.getSession().getId(), executorData.getExecutor().getId());
    	}
		
    	throw new ChoreographerSessionException(sessionStep.getSession().getId(), sessionStep.getId(), "Can't find properly working executor for step: " + createFullyQualifiedStepName(step));
	}

	//-------------------------------------------------------------------------------------------------
	private OrchestrationFormRequestDTO createOrchestrationFormRequestFromServiceQueryForm(final ServiceQueryFormDTO form) {
    	logger.debug("createOrchestrationFormRequestFromServiceQueryForm started...");

	    final OrchestrationFormRequestDTO orchestrationForm = new OrchestrationFormRequestDTO();
	    orchestrationForm.setRequestedService(form);
	    orchestrationForm.setRequesterSystem(requesterSystem);
	    
	    final OrchestrationFlags orchestrationFlags = orchestrationForm.getOrchestrationFlags();
	    orchestrationFlags.put(OrchestrationFlags.Flag.EXTERNAL_SERVICE_REQUEST, true);
	    orchestrationFlags.put(OrchestrationFlags.Flag.MATCHMAKING, true);
	    orchestrationFlags.put(OrchestrationFlags.Flag.OVERRIDE_STORE, true);
	
	    return orchestrationForm;
	}
	
	//-------------------------------------------------------------------------------------------------
	private void regenerateTokensIfAny(final long sessionId, final long sessionStepId, final String fullStepName, final ChoreographerExecutor executor,
									   final List<OrchestrationResultDTO> orchResults) {
		logger.debug("regenerateTokensIfAny started...");
		
		// Query ServiceRegistry in order to have the authInfo of the executor
		final SystemRequestDTO consumer = DTOConverter.convertSystemResponseDTOToSystemRequestDTO(driver.queryServiceRegistryBySystem(executor.getName(),
																																	  executor.getAddress(),
																																	  executor.getPort()));			
		final List<OrchestrationResultDTO> toBeUpdated = new ArrayList<>();
		final List<TokenGenerationRequestDTO> tokenGenerationRequests = new ArrayList<>();
		for (final OrchestrationResultDTO orchResult : orchResults) {
			if (!orchResult.getAuthorizationTokens().isEmpty()) {
				toBeUpdated.add(orchResult);
				final TokenGenerationProviderDTO tokenProviderDTO = createTokenGenerationProviderDTOFromOrchResult(orchResult);
				tokenGenerationRequests.add(new TokenGenerationRequestDTO(consumer, null, List.of(tokenProviderDTO), orchResult.getService().getServiceDefinition()));
			}
		}
		
		final List<TokenGenerationDetailedResponseDTO> tokenData = driver.generateMultiServiceAuthorizationTokens(tokenGenerationRequests).getData();
		updateTokensInOrchestrationResultDTO(sessionId, sessionStepId, fullStepName, toBeUpdated, tokenData);
	}
	
	//-------------------------------------------------------------------------------------------------
	private TokenGenerationProviderDTO createTokenGenerationProviderDTOFromOrchResult(final OrchestrationResultDTO orchResult) {
		logger.debug("createTokenGenerationProviderDTOFromOrchResult started...");
		
		final TokenGenerationProviderDTO tokenProviderDTO = new TokenGenerationProviderDTO();
		tokenProviderDTO.setProvider(DTOConverter.convertSystemResponseDTOToSystemRequestDTO(orchResult.getProvider()));
		tokenProviderDTO.setServiceInterfaces(new ArrayList<>(orchResult.getAuthorizationTokens().keySet())); // we need to generate for the same interfaces
		tokenProviderDTO.setTokenDuration(CoreDefaults.DEFAULT_AUTH_TOKEN_TTL_IN_MINUTES);
		return tokenProviderDTO;
	}
	
	//-------------------------------------------------------------------------------------------------
	private void updateTokensInOrchestrationResultDTO(final long sessionId, final long sessionStepId, final String fullStepName,
													  final List<OrchestrationResultDTO> orchResults, final List<TokenGenerationDetailedResponseDTO> tokenDetailsList) {
		logger.debug("updateTokensInOrchestrationResultDTO started...");
		
		for (final OrchestrationResultDTO orchResult : orchResults) {
			final String serviceDefinition = orchResult.getService().getServiceDefinition();
			
			boolean newTokenFound = false;
			for (final TokenGenerationDetailedResponseDTO tokenDetails : tokenDetailsList) {
				if (serviceDefinition.equalsIgnoreCase(tokenDetails.getService())) {
					final TokenDataDTO tokenData = tokenDetails.getTokenData().get(0); // there was only one provider because of the MATCHMAKING flag, so here we have only one again
					if (orchResult.getProvider().getSystemName().equalsIgnoreCase(tokenData.getProviderName())
							&& orchResult.getProvider().getAddress().equalsIgnoreCase(tokenData.getProviderAddress())
							&& orchResult.getProvider().getPort() == tokenData.getProviderPort()) { 
						
						orchResult.setAuthorizationTokens(tokenData.getTokens());
						newTokenFound = true;
						break;					
					}
				}
			}
			
			if (!newTokenFound) {
				throw new ChoreographerSessionException(sessionId, sessionStepId, "Missing regenerated token at step " + fullStepName + " for service " + serviceDefinition);
			}
		}
	}

	//-------------------------------------------------------------------------------------------------
	private void validatePayload(final ChoreographerExecutedStepResultDTO payload) {
		logger.debug("validatePayload started...");
		
		Assert.notNull(payload, "Payload is null.");
		Assert.isTrue(payload.getSessionId() != null && payload.getSessionId() < 0, "Invalid session id.");
		Assert.isTrue(payload.getSessionStepId() != null && payload.getSessionStepId() < 0, "Invalid session step id.");
		Assert.notNull(payload.getStatus(), "Status is null.");
		
		if (payload.getStatus().isError()) {
			Assert.isTrue(!Utilities.isEmpty(payload.getMessage()), "Message is null or blank.");
		}
	}
	
    //-------------------------------------------------------------------------------------------------
	private void handleSessionStepError(final ChoreographerExecutedStepResultDTO payload) {
		logger.debug("handleSessionStepError started...");
		
		if (payload.getStatus().isFatal()) {
			abortSession(payload.getSessionId(), payload.getSessionStepId(), payload.getMessage() + " " + payload.getException());
		} else {
			// error is not fatal, maybe an other executor can able to do the step
			final ChoreographerSessionStep sessionStep = sessionDBService.getSessionStepById(payload.getSessionStepId());
			final ChoreographerStep step = sessionStep.getStep();
			sessionDBService.worklog(sessionStep.getSession().getPlan().getName(), step.getAction().getName(), step.getName(), payload.getSessionId(), payload.getMessage(), payload.getException());
			final SessionExecutorCache cache = sessionDataStorage.get(payload.getSessionId());
			cache.remove(step.getServiceDefinition(), step.getMinVersion(), step.getMaxVersion());
			cache.getExclusions().add(sessionStep.getExecutor().getId());
			
			final ExecutorData executorData = executorSelector.selectAndInit(payload.getSessionId(), step, cache.getExclusions(), false);
			if (executorData != null) {
				cache.put(step.getServiceDefinition(), step.getMinVersion(), step.getMaxVersion(), executorData);
				sessionDBService.changeSessionStepExecutor(sessionStep.getId(), executorData.getExecutor().getId());
				executeStep(step, payload.getSessionId());
			} else {
				// no replacement executor so we have to abort
				abortSession(payload.getSessionId(), payload.getSessionStepId(), payload.getMessage() + " " + payload.getException());
			}
		}
	}

	//-------------------------------------------------------------------------------------------------
	private void handleSessionStepAborted(final ChoreographerExecutedStepResultDTO payload) {
		logger.debug("handleSessionStepAborted started...");
		
		final ChoreographerSessionStep sessionStep = sessionDBService.getSessionStepById(payload.getSessionStepId());
		final ChoreographerPlan plan = sessionStep.getSession().getPlan();
		final ChoreographerStep step = sessionStep.getStep();
		sessionDBService.worklog(plan.getName(), step.getAction().getName(), step.getName(), payload.getSessionId(), "The executor of this step has aborted successfully." , null);
	}

	//-------------------------------------------------------------------------------------------------
	private void handleSessionStepSuccess(final ChoreographerExecutedStepResultDTO payload) {
		//TOOD: implement this
//      long sessionId = sessionFinishedStepDataDTO.getSessionId();
//      long runningStepId = sessionFinishedStepDataDTO.getRunningStepId();
//
//      //System.out.println(sessionFinishedStepDataDTO.getSessionId() + " " + sessionFinishedStepDataDTO.getRunningStepId());
//
//      choreographerDBService.setRunningStepStatus(runningStepId, ChoreographerSessionStatus.DONE, "Step execution is done.");
//
//      ChoreographerSessionStep runningStep = choreographerDBService.getRunningStepById(runningStepId);
//      ChoreographerStep currentStep = choreographerDBService.getStepById(runningStep.getStep().getId());
//
//      //System.out.println(currentStep.getName());
//
//      if (currentStep.getNextSteps().isEmpty()) {
//          boolean canGoToNextAction = true;
//          logger.debug("Step has no next steps therefore it should be checked if can go to next action.");
//          ChoreographerAction currentAction = currentStep.getAction();
//
//          List<ChoreographerSessionStep> currentRunningStepList = choreographerDBService.getAllRunningStepsBySessionId(sessionId);
//
//          for (ChoreographerSessionStep runningStepInstance : currentRunningStepList) {
//              //System.out.println(runningStepInstance.getId());
//              if (!runningStepInstance.getStatus().equals(ChoreographerSessionStatus.DONE)) {
//                  //System.out.println("canGoToNextStep should be set to false");
//                  canGoToNextAction = false;
//                  break;
//              }
//              if (runningStepInstance.getStep().getNextSteps().isEmpty() && !runningStep.getStatus().equals(ChoreographerSessionStatus.DONE)) {
//                  //System.out.println("-------------------  canGoToNextAction should be set to false!!!! --------------------");
//                  canGoToNextAction = false;
//                  break;
//              }
//          }
//
//          if (canGoToNextAction) {
//              // System.out.println("If there is next Action then it should run now!");
//              ChoreographerAction nextAction = currentAction.getNextAction();
//              if (nextAction != null) {
//                  Set<ChoreographerStep> firstStepsInNewAction = new HashSet<>(nextAction.getFirstStepEntries());
//
//                  firstStepsInNewAction.parallelStream().forEach(firstStepInNewAction -> {
//                      try {
//                          executeStep(firstStepInNewAction, sessionId);
//                      } catch (InterruptedException e) {
//                          e.printStackTrace();
//                      }
//                  });
//              }
//          }
//      }
//
//      for (ChoreographerStepNextStepConnection nextStep : currentStep.getNextSteps()) {
//          boolean allPreviousStepsDone = true;
//
//          //System.out.println(nextStep.getNextStepEntry().getName() + " in for");
//          // Check if all previous steps of the next step are done.
//          for (ChoreographerStepNextStepConnection prevStep : nextStep.getNextStepEntry().getSteps()) {
//              //System.out.println(prevStep.getId() + "    " + prevStep.getStepEntry().getName());
//              ChoreographerSessionStep prevRunningStep = choreographerDBService.getRunningStepBySessionIdAndStepId(sessionId, prevStep.getStepEntry().getId());
//              if (!prevRunningStep.getStatus().equals(ChoreographerSessionStatus.DONE)) {
//                  allPreviousStepsDone = false;
//              }
//          }
//
//          if (allPreviousStepsDone) {
//              // Run next step
//              try {
//                  executeStep(nextStep.getNextStepEntry(), sessionId);
//              } catch (InterruptedException e) {
//                  e.printStackTrace();
//              }
//              //insertInitiatedRunningStep(nextStep.getNextStepEntry().getId(), sessionId);
//          }
//      }		
	}
}