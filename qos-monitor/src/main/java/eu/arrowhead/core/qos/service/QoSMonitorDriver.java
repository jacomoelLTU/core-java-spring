package eu.arrowhead.core.qos.service;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponents;

import eu.arrowhead.common.CommonConstants;
import eu.arrowhead.common.CoreCommonConstants;
import eu.arrowhead.common.core.CoreSystemService;
import eu.arrowhead.common.dto.internal.CloudAccessListResponseDTO;
import eu.arrowhead.common.dto.internal.CloudWithRelaysListResponseDTO;
import eu.arrowhead.common.dto.internal.ServiceRegistryListResponseDTO;
import eu.arrowhead.common.dto.internal.SystemAddressSetRelayResponseDTO;
import eu.arrowhead.common.dto.shared.CloudRequestDTO;
import eu.arrowhead.common.exception.ArrowheadException;
import eu.arrowhead.common.http.HttpService;

@Component
public class QoSMonitorDriver {

	//=================================================================================================
	// members

	private static final String GATEKEEPER_PULL_CLOUDS_URI_KEY = CoreSystemService.GATEKEEPER_PULL_CLOUDS.getServiceDefinition() + CoreCommonConstants.URI_SUFFIX;
	private static final String GATEKEEPER_COLLECT_SYSTEM_ADDRESSES_URI_KEY = CoreSystemService.GATEKEEPER_COLLECT_SYSTEM_ADDRESSES.getServiceDefinition() + CoreCommonConstants.URI_SUFFIX;
	private static final String GATEKEEPER_COLLECT_ACCESS_TYPES_URI_KEY = CoreSystemService.GATEKEEPER_COLLECT_ACCESS_TYPES.getServiceDefinition() + CoreCommonConstants.URI_SUFFIX;

	public static final String KEY_CALCULATED_SERVICE_TIME_FRAME = "QoSCalculatedServiceTimeFrame";

	private static final Logger logger = LogManager.getLogger(QoSMonitorDriver.class);

	@Autowired
	private HttpService httpService;

	@Resource(name = CommonConstants.ARROWHEAD_CONTEXT)
	private Map<String,Object> arrowheadContext;

	//=================================================================================================
	// methods

	//-------------------------------------------------------------------------------------------------
	public ServiceRegistryListResponseDTO queryServiceRegistryAll() {
		logger.debug("queryServiceRegistryAll started...");

		try {
			final UriComponents queryBySystemDTOUri = getQueryAllUri();
			final ResponseEntity<ServiceRegistryListResponseDTO> response = httpService.sendRequest(queryBySystemDTOUri, HttpMethod.GET, ServiceRegistryListResponseDTO.class);

			return response.getBody();

		} catch (final ArrowheadException ex) {

			logger.debug("Exception: " + ex.getMessage());
			throw ex;

		}

	}

	//-------------------------------------------------------------------------------------------------
	public CloudWithRelaysListResponseDTO queryGatekeeperAllCloud() {
		logger.debug("queryGatekeeperAllCloud started...");

		try {
			final UriComponents queryByAllCloudsUri = getGatekeeperAllCloudsUri();
			final ResponseEntity<CloudWithRelaysListResponseDTO> response = httpService.sendRequest(queryByAllCloudsUri, HttpMethod.GET, CloudWithRelaysListResponseDTO.class);

			return response.getBody();

		} catch (final ArrowheadException ex) {

			logger.debug("Exception: " + ex.getMessage());
			throw ex;

		}

	}

	//-------------------------------------------------------------------------------------------------
	public CloudAccessListResponseDTO queryGatekeeperGatewayIsMandatory(final List<CloudRequestDTO> cloudList) {
		logger.debug("queryGatekeeperGatewayIsMandatory started...");

		try {
			final UriComponents queryByGatewayIsMandatoryUri = getGatekeeperGatewayIsMandatoryUri();
			final ResponseEntity<CloudAccessListResponseDTO> response = httpService.sendRequest(queryByGatewayIsMandatoryUri, HttpMethod.POST, CloudAccessListResponseDTO.class, cloudList);

			return response.getBody();

		} catch (final ArrowheadException ex) {

			logger.debug("Exception: " + ex.getMessage());
			throw ex;

		}

	}

	//-------------------------------------------------------------------------------------------------
	public SystemAddressSetRelayResponseDTO queryGatekeeperAllSystemAddresses(final CloudRequestDTO cloud) {
		logger.debug("queryGatekeeperAllSystemAddresses started...");

		try {
			final UriComponents queryByAllSystemsUri = getGatekeeperAllSystemsUri();
			final ResponseEntity<SystemAddressSetRelayResponseDTO> response = httpService.sendRequest(queryByAllSystemsUri, HttpMethod.POST, SystemAddressSetRelayResponseDTO.class, cloud);

			return response.getBody();

		} catch (final ArrowheadException ex) {

			logger.debug("Exception: " + ex.getMessage());
			throw ex;

		}

	}

	//=================================================================================================
	// assistant methods
	
	//-------------------------------------------------------------------------------------------------
	private UriComponents getQueryAllUri() {
		logger.debug("getQueryUri started...");

		if (arrowheadContext.containsKey(CoreCommonConstants.SR_QUERY_ALL)) {
			try {
				return (UriComponents) arrowheadContext.get(CoreCommonConstants.SR_QUERY_ALL);
			} catch (final ClassCastException ex) {
				throw new ArrowheadException("QoS Mointor can't find Service Registry Query All URI.");
			}
		} else {
			throw new ArrowheadException("QoS Mointor can't find Service Registry Query All URI.");
		}
	}

	//-------------------------------------------------------------------------------------------------
	private UriComponents getGatekeeperAllCloudsUri() {
		logger.debug("getGatekeeperAllCloudsUri started...");

		if (arrowheadContext.containsKey(GATEKEEPER_PULL_CLOUDS_URI_KEY)) {
			try {
				return (UriComponents) arrowheadContext.get(GATEKEEPER_PULL_CLOUDS_URI_KEY);
			} catch (final ClassCastException ex) {
				throw new ArrowheadException("QoS Mointor can't find gatekeeper all_clouds URI.");
			}
		}

		throw new ArrowheadException("QoS Mointor can't find gatekeeper all_clouds URI.");
	}

	//-------------------------------------------------------------------------------------------------
	private UriComponents getGatekeeperGatewayIsMandatoryUri() {
		logger.debug("getGatekeeperGatewayIsMandatoryUri started...");

		if (arrowheadContext.containsKey(GATEKEEPER_COLLECT_ACCESS_TYPES_URI_KEY)) {
			try {
				return (UriComponents) arrowheadContext.get(GATEKEEPER_COLLECT_ACCESS_TYPES_URI_KEY);
			} catch (final ClassCastException ex) {
				throw new ArrowheadException("QoS Mointor can't find gatekeeper gateway_is_mandatory URI.");
			}
		}
		
		throw new ArrowheadException("QoS Mointor can't find gatekeeper gateway_is_mandatory URI.");
	}

	//-------------------------------------------------------------------------------------------------
	private UriComponents getGatekeeperAllSystemsUri() {
		logger.debug("getGatekeeperAllSystemsUri started...");

		if (arrowheadContext.containsKey(GATEKEEPER_COLLECT_SYSTEM_ADDRESSES_URI_KEY)) {
			try {
				return (UriComponents) arrowheadContext.get(GATEKEEPER_COLLECT_SYSTEM_ADDRESSES_URI_KEY);
			} catch (final ClassCastException ex) {
				throw new ArrowheadException("QoS Mointor can't find gatekeeper all_systems URI.");
			}
		}

		throw new ArrowheadException("QoS Mointor can't find gatekeeper all_systems URI.");
	}

}
