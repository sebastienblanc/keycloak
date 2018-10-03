/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.forms;

import org.apache.http.client.utils.URLEncodedUtils;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.graphene.page.Page;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory;
import org.keycloak.events.Details;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowBindings;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.authentication.PushButtonAuthenticatorFactory;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.ErrorPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;
import org.keycloak.util.BasicAuthHelper;
import org.openqa.selenium.By;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Test that clients can override auth flows
 *
 * @author <a href="mailto:bburke@redhat.com">Bill Burke</a>
 */
public class FlowOverrideTest extends AbstractTestRealmKeycloakTest {

    public static final String TEST_APP_DIRECT_OVERRIDE = "test-app-direct-override";
    public static final String TEST_APP_FLOW = "test-app-flow";
    public static final String TEST_APP_HTTP_CHALLENGE = "http-challenge-client";

    @Rule
    public AssertEvents events = new AssertEvents(this);

    @Page
    protected AppPage appPage;

    @Page
    protected LoginPage loginPage;

    @Page
    protected ErrorPage errorPage;

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
    }

    @Deployment
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(UserResource.class)
                .addPackages(true, "org.keycloak.testsuite");
    }

    /*
     * The only purpose of this class is to serialize data obtained from oauth field
     * and pass it to the server.
     */
    static class SerializationTrick implements Serializable {

        final String applicationBaseUrl;
        final String applicationManagementUrl;
        final String applicationRedirectUrl;

        SerializationTrick(String applicationBaseUrl, String applicationManagementUrl, String applicationRedirectUrl) {
            this.applicationBaseUrl = applicationBaseUrl;
            this.applicationManagementUrl = applicationManagementUrl;
            this.applicationRedirectUrl = applicationRedirectUrl;
        }
    }

    @Before
    public void setupFlows() {
        SerializationTrick serializationTrick = new SerializationTrick(oauth.APP_AUTH_ROOT, oauth.APP_ROOT + "/admin", oauth.APP_AUTH_ROOT + "/*");

        testingClient.server().run(session -> {
            RealmModel realm = session.realms().getRealmByName("test");

            ClientModel client = session.realms().getClientByClientId("test-app-flow", realm);
            if (client != null) {
                return;
            }

            client = session.realms().getClientByClientId("test-app", realm);
            client.setDirectAccessGrantsEnabled(true);

            // Parent flow
            AuthenticationFlowModel browser = new AuthenticationFlowModel();
            browser.setAlias("parent-flow");
            browser.setDescription("browser based authentication");
            browser.setProviderId("basic-flow");
            browser.setTopLevel(true);
            browser.setBuiltIn(true);
            browser = realm.addAuthenticationFlow(browser);

            // Subflow2
            AuthenticationFlowModel subflow2 = new AuthenticationFlowModel();
            subflow2.setTopLevel(false);
            subflow2.setBuiltIn(true);
            subflow2.setAlias("subflow-2");
            subflow2.setDescription("username+password AND pushButton");
            subflow2.setProviderId("basic-flow");
            subflow2 = realm.addAuthenticationFlow(subflow2);

            AuthenticationExecutionModel execution = new AuthenticationExecutionModel();
            execution.setParentFlow(browser.getId());
            execution.setRequirement(AuthenticationExecutionModel.Requirement.ALTERNATIVE);
            execution.setFlowId(subflow2.getId());
            execution.setPriority(20);
            execution.setAuthenticatorFlow(true);
            realm.addAuthenticatorExecution(execution);

            // Subflow2 - push the button
            execution = new AuthenticationExecutionModel();
            execution.setParentFlow(subflow2.getId());
            execution.setRequirement(AuthenticationExecutionModel.Requirement.REQUIRED);
            execution.setAuthenticator(PushButtonAuthenticatorFactory.PROVIDER_ID);
            execution.setPriority(10);
            execution.setAuthenticatorFlow(false);

            realm.addAuthenticatorExecution(execution);

            // Subflow2 - username-password
            execution = new AuthenticationExecutionModel();
            execution.setParentFlow(subflow2.getId());
            execution.setRequirement(AuthenticationExecutionModel.Requirement.REQUIRED);
            execution.setAuthenticator(UsernamePasswordFormFactory.PROVIDER_ID);
            execution.setPriority(20);
            execution.setAuthenticatorFlow(false);

            realm.addAuthenticatorExecution(execution);

            client = realm.addClient(TEST_APP_FLOW);
            client.setSecret("password");
            client.setBaseUrl(serializationTrick.applicationBaseUrl);
            client.setManagementUrl(serializationTrick.applicationManagementUrl);
            client.setEnabled(true);
            client.addRedirectUri(serializationTrick.applicationRedirectUrl);
            client.setAuthenticationFlowBindingOverride(AuthenticationFlowBindings.BROWSER_BINDING, browser.getId());
            client.setPublicClient(false);

            // Parent flow
            AuthenticationFlowModel directGrant = new AuthenticationFlowModel();
            directGrant.setAlias("direct-override-flow");
            directGrant.setDescription("direct grant based authentication");
            directGrant.setProviderId("basic-flow");
            directGrant.setTopLevel(true);
            directGrant.setBuiltIn(true);
            directGrant = realm.addAuthenticationFlow(directGrant);

            execution = new AuthenticationExecutionModel();
            execution.setParentFlow(directGrant.getId());
            execution.setRequirement(AuthenticationExecutionModel.Requirement.REQUIRED);
            execution.setAuthenticator(UsernameOnlyAuthenticator.PROVIDER_ID);
            execution.setPriority(10);
            execution.setAuthenticatorFlow(false);

            realm.addAuthenticatorExecution(execution);

            client = realm.addClient(TEST_APP_DIRECT_OVERRIDE);
            client.setSecret("password");
            client.setBaseUrl(serializationTrick.applicationBaseUrl);
            client.setManagementUrl(serializationTrick.applicationManagementUrl);
            client.setEnabled(true);
            client.addRedirectUri(serializationTrick.applicationRedirectUrl);
            client.setPublicClient(false);
            client.setDirectAccessGrantsEnabled(true);
            client.setAuthenticationFlowBindingOverride(AuthenticationFlowBindings.BROWSER_BINDING, browser.getId());
            client.setAuthenticationFlowBindingOverride(AuthenticationFlowBindings.DIRECT_GRANT_BINDING, directGrant.getId());


            client = realm.addClient(TEST_APP_HTTP_CHALLENGE);
            client.setSecret("password");
            client.setBaseUrl(serializationTrick.applicationBaseUrl);
            client.setManagementUrl(serializationTrick.applicationManagementUrl);
            client.setEnabled(true);
            client.addRedirectUri(serializationTrick.applicationRedirectUrl);
            client.setPublicClient(true);
            client.setDirectAccessGrantsEnabled(true);
            client.setAuthenticationFlowBindingOverride(AuthenticationFlowBindings.DIRECT_GRANT_BINDING, realm.getFlowByAlias("http challenge").getId());
            client.setAuthenticationFlowBindingOverride(AuthenticationFlowBindings.BROWSER_BINDING, realm.getFlowByAlias("http challenge").getId());
        });
    }

    //@Test
    public void testRunConsole() throws Exception {
        Thread.sleep(10000000);
    }


    @Test
    public void testWithClientBrowserOverride() throws Exception {
        oauth.clientId(TEST_APP_FLOW);
        String loginFormUrl = oauth.getLoginFormUrl();
        log.info("loginFormUrl: " + loginFormUrl);

        //Thread.sleep(10000000);

        driver.navigate().to(loginFormUrl);

        Assert.assertEquals("PushTheButton", driver.getTitle());

        // Push the button. I am redirected to username+password form
        driver.findElement(By.name("submit1")).click();


        loginPage.assertCurrent();

        // Fill username+password. I am successfully authenticated
        oauth.fillLoginForm("test-user@localhost", "password");
        appPage.assertCurrent();

        events.expectLogin().client("test-app-flow").detail(Details.USERNAME, "test-user@localhost").assertEvent();
    }

    @Test
    public void testNoOverrideBrowser() throws Exception {
        String clientId = "test-app";
        testNoOverrideBrowser(clientId);
    }

    private void testNoOverrideBrowser(String clientId) {
        oauth.clientId(clientId);
        String loginFormUrl = oauth.getLoginFormUrl();
        log.info("loginFormUrl: " + loginFormUrl);

        //Thread.sleep(10000000);

        driver.navigate().to(loginFormUrl);

        loginPage.assertCurrent();

        // Fill username+password. I am successfully authenticated
        oauth.fillLoginForm("test-user@localhost", "password");
        appPage.assertCurrent();

        events.expectLogin().client(clientId).detail(Details.USERNAME, "test-user@localhost").assertEvent();
    }

    @Test
    public void testGrantAccessTokenNoOverride() throws Exception {
        testDirectGrantNoOverride("test-app");
    }

    private void testDirectGrantNoOverride(String clientId) {
        Client httpClient = javax.ws.rs.client.ClientBuilder.newClient();
        String grantUri = oauth.getResourceOwnerPasswordCredentialGrantUrl();
        WebTarget grantTarget = httpClient.target(grantUri);

        {   // test no password
            String header = BasicAuthHelper.createHeader(clientId, "password");
            Form form = new Form();
            form.param(OAuth2Constants.GRANT_TYPE, OAuth2Constants.PASSWORD);
            form.param("username", "test-user@localhost");
            Response response = grantTarget.request()
                    .header(HttpHeaders.AUTHORIZATION, header)
                    .post(Entity.form(form));
            assertEquals(401, response.getStatus());
            response.close();
        }

        {   // test invalid password
            String header = BasicAuthHelper.createHeader(clientId, "password");
            Form form = new Form();
            form.param(OAuth2Constants.GRANT_TYPE, OAuth2Constants.PASSWORD);
            form.param("username", "test-user@localhost");
            form.param("password", "invalid");
            Response response = grantTarget.request()
                    .header(HttpHeaders.AUTHORIZATION, header)
                    .post(Entity.form(form));
            assertEquals(401, response.getStatus());
            response.close();
        }

        {   // test valid password
            String header = BasicAuthHelper.createHeader(clientId, "password");
            Form form = new Form();
            form.param(OAuth2Constants.GRANT_TYPE, OAuth2Constants.PASSWORD);
            form.param("username", "test-user@localhost");
            form.param("password", "password");
            Response response = grantTarget.request()
                    .header(HttpHeaders.AUTHORIZATION, header)
                    .post(Entity.form(form));
            assertEquals(200, response.getStatus());
            response.close();
        }

        httpClient.close();
        events.clear();
    }

    @Test
    public void testGrantAccessTokenWithClientOverride() throws Exception {
        String clientId = TEST_APP_DIRECT_OVERRIDE;
        Client httpClient = javax.ws.rs.client.ClientBuilder.newClient();
        String grantUri = oauth.getResourceOwnerPasswordCredentialGrantUrl();
        WebTarget grantTarget = httpClient.target(grantUri);

        {   // test no password
            String header = BasicAuthHelper.createHeader(clientId, "password");
            Form form = new Form();
            form.param(OAuth2Constants.GRANT_TYPE, OAuth2Constants.PASSWORD);
            form.param("username", "test-user@localhost");
            Response response = grantTarget.request()
                    .header(HttpHeaders.AUTHORIZATION, header)
                    .post(Entity.form(form));
            assertEquals(200, response.getStatus());
            response.close();
        }

        httpClient.close();
        events.clear();
    }

    @Test
    public void testClientOverrideFlowUsingDirectGrantHttpChallenge() {
        Client httpClient = javax.ws.rs.client.ClientBuilder.newClient();
        String grantUri = oauth.getResourceOwnerPasswordCredentialGrantUrl();
        WebTarget grantTarget = httpClient.target(grantUri);

        // no username/password
        Form form = new Form();
        form.param(OAuth2Constants.GRANT_TYPE, OAuth2Constants.PASSWORD);
        form.param(OAuth2Constants.CLIENT_ID, TEST_APP_HTTP_CHALLENGE);
        Response response = grantTarget.request()
                .post(Entity.form(form));
        assertEquals("Basic realm=\"test\"", response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals(401, response.getStatus());
        response.close();

        // now, username password using basic challenge response
        response = grantTarget.request()
                .header(HttpHeaders.AUTHORIZATION, BasicAuthHelper.createHeader("test-user@localhost", "password"))
                .post(Entity.form(form));
        assertEquals(200, response.getStatus());
        response.close();

        httpClient.close();
        events.clear();
    }

    @Test
    public void testClientOverrideFlowUsingBrowserHttpChallenge() {
        Client httpClient = javax.ws.rs.client.ClientBuilder.newClient();
        oauth.clientId(TEST_APP_HTTP_CHALLENGE);
        String grantUri = oauth.getLoginFormUrl();
        WebTarget grantTarget = httpClient.target(grantUri);

        Response response = grantTarget.request().get();
        assertEquals(302, response.getStatus());
        String location = response.getHeaderString(HttpHeaders.LOCATION);
        response.close();

        // first challenge
        response = httpClient.target(location).request().get();
        assertEquals("Basic realm=\"test\"", response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals(401, response.getStatus());
        response.close();

        // now, username password using basic challenge response
        response = httpClient.target(location).request()
                .header(HttpHeaders.AUTHORIZATION, BasicAuthHelper.createHeader("test-user@localhost", "password"))
                .post(Entity.form(new Form()));
        assertEquals(302, response.getStatus());
        location = response.getHeaderString(HttpHeaders.LOCATION);
        response.close();

        Form form = new Form();

        form.param(OAuth2Constants.GRANT_TYPE, OAuth2Constants.AUTHORIZATION_CODE);
        form.param(OAuth2Constants.CLIENT_ID, TEST_APP_HTTP_CHALLENGE);
        form.param(OAuth2Constants.REDIRECT_URI, oauth.APP_AUTH_ROOT);
        form.param(OAuth2Constants.CODE, location.substring(location.indexOf(OAuth2Constants.CODE) + OAuth2Constants.CODE.length() + 1));

        // exchange code to token
        response = httpClient.target(oauth.getAccessTokenUrl()).request()
                .post(Entity.form(form));
        assertEquals(200, response.getStatus());
        response.close();

        httpClient.close();
        events.clear();
    }

    @Test
    public void testRestInterface() throws Exception {
        ClientsResource clients = adminClient.realm("test").clients();
        List<ClientRepresentation> query = clients.findByClientId(TEST_APP_DIRECT_OVERRIDE);
        ClientRepresentation clientRep = query.get(0);
        String directGrantFlowId = clientRep.getAuthenticationFlowBindingOverrides().get(AuthenticationFlowBindings.DIRECT_GRANT_BINDING);
        Assert.assertNotNull(directGrantFlowId);
        clientRep.getAuthenticationFlowBindingOverrides().put(AuthenticationFlowBindings.DIRECT_GRANT_BINDING, "");
        clients.get(clientRep.getId()).update(clientRep);
        testDirectGrantNoOverride(TEST_APP_DIRECT_OVERRIDE);
        clientRep.getAuthenticationFlowBindingOverrides().put(AuthenticationFlowBindings.DIRECT_GRANT_BINDING, directGrantFlowId);
        clients.get(clientRep.getId()).update(clientRep);
        testGrantAccessTokenWithClientOverride();

        query = clients.findByClientId(TEST_APP_FLOW);
        clientRep = query.get(0);
        String browserFlowId = clientRep.getAuthenticationFlowBindingOverrides().get(AuthenticationFlowBindings.BROWSER_BINDING);
        Assert.assertNotNull(browserFlowId);
        clientRep.getAuthenticationFlowBindingOverrides().put(AuthenticationFlowBindings.BROWSER_BINDING, "");
        clients.get(clientRep.getId()).update(clientRep);
        testNoOverrideBrowser(TEST_APP_FLOW);
        clientRep.getAuthenticationFlowBindingOverrides().put(AuthenticationFlowBindings.BROWSER_BINDING, browserFlowId);
        clients.get(clientRep.getId()).update(clientRep);
        testWithClientBrowserOverride();

        query = clients.findByClientId(TEST_APP_FLOW);
        clientRep = query.get(0);
        clientRep.getAuthenticationFlowBindingOverrides().put(AuthenticationFlowBindings.BROWSER_BINDING, "bad-id");
        try {
            clients.get(clientRep.getId()).update(clientRep);
            Assert.fail();
        } catch (Exception e) {

        }
        query = clients.findByClientId(TEST_APP_FLOW);
        clientRep = query.get(0);
        Assert.assertEquals(browserFlowId, clientRep.getAuthenticationFlowBindingOverrides().get(AuthenticationFlowBindings.BROWSER_BINDING));

    }

}
