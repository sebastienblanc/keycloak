package org.keycloak.testsuite.forms;

import java.io.Serializable;

/**
 * The only purpose of this class is to serialize data obtained from oauth field
 * and pass it to the server.
 */
class SerializableApplicationData implements Serializable {

    final String applicationBaseUrl;
    final String applicationManagementUrl;
    final String applicationRedirectUrl;

    SerializableApplicationData(String applicationBaseUrl, String applicationManagementUrl, String applicationRedirectUrl) {
        this.applicationBaseUrl = applicationBaseUrl;
        this.applicationManagementUrl = applicationManagementUrl;
        this.applicationRedirectUrl = applicationRedirectUrl;
    }
}
