// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.commissioner.CallHome;
import com.yugabyte.yw.commissioner.HealthChecker;
import com.yugabyte.yw.commissioner.QueryAlerts;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.ConfigHelper;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.alerts.AlertConfigurationWriter;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.scheduler.Scheduler;
import org.junit.After;
import org.junit.Test;
import org.pac4j.play.CallbackController;
import org.pac4j.play.store.PlayCacheSessionStore;
import org.pac4j.play.store.PlaySessionStore;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.mvc.Result;
import play.test.Helpers;

import java.util.Map;
import java.util.UUID;

import static com.yugabyte.yw.common.ApiUtils.getTestUserIntent;
import static com.yugabyte.yw.common.AssertHelper.*;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthToken;
import static com.yugabyte.yw.models.Users.Role;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static play.inject.Bindings.bind;
import static play.mvc.Http.Status.*;
import static play.test.Helpers.*;

public class SessionControllerTest {

  HealthChecker mockHealthChecker;
  Scheduler mockScheduler;
  CallHome mockCallHome;
  CallbackController mockCallbackController;
  PlayCacheSessionStore mockSessionStore;
  QueryAlerts mockQueryAlerts;
  AlertConfigurationWriter mockAlertConfigurationWriter;

  Application app;

  private void startApp(boolean isMultiTenant) {
    mockHealthChecker = mock(HealthChecker.class);
    mockScheduler = mock(Scheduler.class);
    mockCallHome = mock(CallHome.class);
    mockCallbackController = mock(CallbackController.class);
    mockSessionStore = mock(PlayCacheSessionStore.class);
    mockQueryAlerts = mock(QueryAlerts.class);
    mockAlertConfigurationWriter = mock(AlertConfigurationWriter.class);
    app =
        new GuiceApplicationBuilder()
            .configure((Map) Helpers.inMemoryDatabase())
            .configure(ImmutableMap.of("yb.multiTenant", isMultiTenant))
            .overrides(bind(Scheduler.class).toInstance(mockScheduler))
            .overrides(bind(HealthChecker.class).toInstance(mockHealthChecker))
            .overrides(bind(CallHome.class).toInstance(mockCallHome))
            .overrides(bind(CallbackController.class).toInstance(mockCallbackController))
            .overrides(bind(PlaySessionStore.class).toInstance(mockSessionStore))
            .overrides(bind(QueryAlerts.class).toInstance(mockQueryAlerts))
            .overrides(
                bind(AlertConfigurationWriter.class).toInstance(mockAlertConfigurationWriter))
            .build();
    Helpers.start(app);
  }

  @After
  public void tearDown() {
    Helpers.stop(app);
  }

  @Test
  public void testValidLogin() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer();
    ModelFactory.testUser(customer);
    ObjectNode loginJson = Json.newObject();
    loginJson.put("email", "test@customer.com");
    loginJson.put("password", "password");
    Result result = route(fakeRequest("POST", "/api/login").bodyJson(loginJson));
    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    assertNotNull(json.get("authToken"));
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testLoginWithInvalidPassword() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer();
    ModelFactory.testUser(customer);
    ObjectNode loginJson = Json.newObject();
    loginJson.put("email", "test@customer.com");
    loginJson.put("password", "password1");
    Result result = route(fakeRequest("POST", "/api/login").bodyJson(loginJson));
    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(UNAUTHORIZED, result.status());
    assertThat(
        json.get("error").toString(),
        allOf(notNullValue(), containsString("Invalid User Credentials")));
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testLoginWithNullPassword() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer();
    ModelFactory.testUser(customer);
    ObjectNode loginJson = Json.newObject();
    loginJson.put("email", "test@customer.com");
    Result result = assertYWSE(() -> route(fakeRequest("POST", "/api/login").bodyJson(loginJson)));
    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(BAD_REQUEST, result.status());
    assertThat(
        json.get("error").toString(),
        allOf(notNullValue(), containsString("{\"password\":[\"This field is required\"]}")));
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testInsecureLoginValid() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    ModelFactory.testUser(customer, "tc1@test.com", Role.ReadOnly);
    ConfigHelper configHelper = new ConfigHelper();
    configHelper.loadConfigToDB(
        ConfigHelper.ConfigType.Security, ImmutableMap.of("level", "insecure"));

    Result result = route(fakeRequest("GET", "/api/insecure_login"));
    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    assertNotNull(json.get("apiToken"));
    assertNotNull(json.get("customerUUID"));
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testInsecureLoginWithoutReadOnlyUser() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    ModelFactory.testUser(customer, "tc1@test.com", Role.Admin);
    ConfigHelper configHelper = new ConfigHelper();
    configHelper.loadConfigToDB(
        ConfigHelper.ConfigType.Security, ImmutableMap.of("level", "insecure"));

    Result result = route(fakeRequest("GET", "/api/insecure_login"));
    assertUnauthorized(result, "No read only customer exists.");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testInsecureLoginInvalid() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    ModelFactory.testUser(customer);

    Result result = route(fakeRequest("GET", "/api/insecure_login"));

    assertUnauthorized(result, "Insecure login unavailable.");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testRegisterCustomer() {
    startApp(true);
    ObjectNode registerJson = Json.newObject();
    registerJson.put("code", "fb");
    registerJson.put("email", "foo2@bar.com");
    registerJson.put("password", "pAssw_0rd");
    registerJson.put("name", "Foo");

    Result result = route(fakeRequest("POST", "/api/register").bodyJson(registerJson));
    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    assertNotNull(json.get("authToken"));
    Customer c1 = Customer.get(UUID.fromString(json.get("customerUUID").asText()));

    ObjectNode loginJson = Json.newObject();
    loginJson.put("email", "foo2@bar.com");
    loginJson.put("password", "pAssw_0rd");
    result = route(fakeRequest("POST", "/api/login").bodyJson(loginJson));
    json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    assertNotNull(json.get("authToken"));
    assertAuditEntry(0, c1.uuid);
    assertNotNull(AlertRoute.getDefaultRoute(c1.uuid));
  }

  @Test
  public void testRegisterCustomerWrongPassword() {
    startApp(true);
    ObjectNode registerJson = Json.newObject();
    registerJson.put("code", "fb");
    registerJson.put("email", "foo2@bar.com");
    registerJson.put("password", "pAssw0rd");
    registerJson.put("name", "Foo");

    Result result =
        assertYWSE(() -> route(fakeRequest("POST", "/api/register").bodyJson(registerJson)));

    assertEquals(BAD_REQUEST, result.status());
  }

  @Test
  public void testRegisterMultiCustomer() {
    startApp(true);
    ObjectNode registerJson = Json.newObject();
    registerJson.put("code", "fb");
    registerJson.put("email", "foo2@bar.com");
    registerJson.put("password", "pAssw_0rd");
    registerJson.put("name", "Foo");

    Result result = route(fakeRequest("POST", "/api/register").bodyJson(registerJson));
    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    assertNotNull(json.get("authToken"));
    String authToken = json.get("authToken").asText();
    Customer c1 = Customer.get(UUID.fromString(json.get("customerUUID").asText()));

    ObjectNode registerJson2 = Json.newObject();
    registerJson2.put("code", "fb");
    registerJson2.put("email", "foo3@bar.com");
    registerJson2.put("password", "pAssw_0rd");
    registerJson2.put("name", "Foo");

    result =
        route(
            fakeRequest("POST", "/api/register")
                .bodyJson(registerJson2)
                .header("X-AUTH-TOKEN", authToken));
    json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    assertNotNull(json.get("authToken"));
    assertAuditEntry(0, c1.uuid);
  }

  @Test
  public void testRegisterMultiCustomerNoAuth() {
    startApp(true);
    ObjectNode registerJson = Json.newObject();
    registerJson.put("code", "fb");
    registerJson.put("email", "foo2@bar.com");
    registerJson.put("password", "pAssw_0rd");
    registerJson.put("name", "Foo");

    Result result = route(fakeRequest("POST", "/api/register").bodyJson(registerJson));
    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    assertNotNull(json.get("authToken"));
    String authToken = json.get("authToken").asText();
    Customer c1 = Customer.get(UUID.fromString(json.get("customerUUID").asText()));

    ObjectNode registerJson2 = Json.newObject();
    registerJson2.put("code", "fb");
    registerJson2.put("email", "foo3@bar.com");
    registerJson2.put("password", "pAssw_0rd");
    registerJson2.put("name", "Foo");

    result = route(fakeRequest("POST", "/api/register").bodyJson(registerJson2));

    assertBadRequest(result, "Only Super Admins can register tenant.");
  }

  @Test
  public void testRegisterMultiCustomerWrongAuth() {
    startApp(true);
    ObjectNode registerJson = Json.newObject();
    registerJson.put("code", "fb");
    registerJson.put("email", "foo2@bar.com");
    registerJson.put("password", "pAssw_0rd");
    registerJson.put("name", "Foo");

    Result result = route(fakeRequest("POST", "/api/register").bodyJson(registerJson));
    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    assertNotNull(json.get("authToken"));
    String authToken = json.get("authToken").asText();
    Customer c1 = Customer.get(UUID.fromString(json.get("customerUUID").asText()));

    ObjectNode registerJson2 = Json.newObject();
    registerJson2.put("code", "fb");
    registerJson2.put("email", "foo3@bar.com");
    registerJson2.put("password", "pAssw_0rd");
    registerJson2.put("name", "Foo");

    result =
        route(
            fakeRequest("POST", "/api/register")
                .bodyJson(registerJson2)
                .header("X-AUTH-TOKEN", authToken));
    json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    assertNotNull(json.get("authToken"));
    String authToken2 = json.get("authToken").asText();

    ObjectNode registerJson3 = Json.newObject();
    registerJson3.put("code", "fb");
    registerJson3.put("email", "foo4@bar.com");
    registerJson3.put("password", "pAssw_0rd");
    registerJson3.put("name", "Foo");

    result =
        route(
            fakeRequest("POST", "/api/register")
                .bodyJson(registerJson3)
                .header("X-AUTH-TOKEN", authToken2));

    assertBadRequest(result, "Only Super Admins can register tenant.");
  }

  @Test
  public void testRegisterCustomerWithLongerCode() {
    startApp(true);
    ObjectNode registerJson = Json.newObject();
    registerJson.put("code", "abcabcabcabcabcabc");
    registerJson.put("email", "foo2@bar.com");
    registerJson.put("password", "pAssw_0rd");
    registerJson.put("name", "Foo");

    Result result =
        assertYWSE(() -> route(fakeRequest("POST", "/api/register").bodyJson(registerJson)));
    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(BAD_REQUEST, result.status());
    assertValue(json, "error", "{\"code\":[\"Maximum length is 15\"]}");
  }

  @Test
  public void testRegisterCustomerExceedingLimit() {
    startApp(false);
    ModelFactory.testCustomer("Test Customer 1");
    ObjectNode registerJson = Json.newObject();
    registerJson.put("code", "fb");
    registerJson.put("email", "foo2@bar.com");
    registerJson.put("password", "pAssw_0rd");
    registerJson.put("name", "Foo");
    Result result = route(fakeRequest("POST", "/api/register").bodyJson(registerJson));
    assertBadRequest(result, "Cannot register multiple accounts in Single tenancy.");
  }

  @Test
  public void testRegisterCustomerWithoutEmail() {
    startApp(false);
    ObjectNode registerJson = Json.newObject();
    registerJson.put("email", "test@customer.com");
    Result result =
        assertYWSE(() -> route(fakeRequest("POST", "/api/login").bodyJson(registerJson)));

    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(BAD_REQUEST, result.status());
    assertThat(
        json.get("error").toString(),
        allOf(notNullValue(), containsString("{\"password\":[\"This field is required\"]}")));
  }

  @Test
  public void testLogout() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    ModelFactory.testUser(customer);
    ObjectNode loginJson = Json.newObject();
    loginJson.put("email", "test@customer.com");
    loginJson.put("password", "password");
    Result result = route(fakeRequest("POST", "/api/login").bodyJson(loginJson));
    JsonNode json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    String authToken = json.get("authToken").asText();
    result = route(fakeRequest("GET", "/api/logout").header("X-AUTH-TOKEN", authToken));
    assertEquals(OK, result.status());
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testAuthTokenExpiry() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    ModelFactory.testUser(customer);
    ObjectNode loginJson = Json.newObject();
    loginJson.put("email", "test@customer.com");
    loginJson.put("password", "password");
    Result result = route(fakeRequest("POST", "/api/login").bodyJson(loginJson));
    JsonNode json = Json.parse(contentAsString(result));
    String authToken1 = json.get("authToken").asText();
    loginJson.put("email", "test@customer.com");
    loginJson.put("password", "password");
    result = route(fakeRequest("POST", "/api/login").bodyJson(loginJson));
    json = Json.parse(contentAsString(result));
    String authToken2 = json.get("authToken").asText();
    assertEquals(authToken1, authToken2);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testApiTokenUpsert() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    ModelFactory.testUser(customer);
    ObjectNode loginJson = Json.newObject();
    loginJson.put("email", "test@customer.com");
    loginJson.put("password", "password");
    Result result = route(fakeRequest("POST", "/api/login").bodyJson(loginJson));
    JsonNode json = Json.parse(contentAsString(result));
    String authToken = json.get("authToken").asText();
    String custUuid = json.get("customerUUID").asText();
    ObjectNode apiTokenJson = Json.newObject();
    apiTokenJson.put("authToken", authToken);
    result =
        route(
            fakeRequest("PUT", "/api/customers/" + custUuid + "/api_token")
                .header("X-AUTH-TOKEN", authToken));
    json = Json.parse(contentAsString(result));

    assertEquals(OK, result.status());
    assertNotNull(json.get("apiToken"));
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testApiTokenUpdate() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    ModelFactory.testUser(customer);
    ObjectNode loginJson = Json.newObject();
    loginJson.put("email", "test@customer.com");
    loginJson.put("password", "password");
    Result result = route(fakeRequest("POST", "/api/login").bodyJson(loginJson));
    JsonNode json = Json.parse(contentAsString(result));
    String authToken = json.get("authToken").asText();
    String custUuid = json.get("customerUUID").asText();
    ObjectNode apiTokenJson = Json.newObject();
    apiTokenJson.put("authToken", authToken);
    result =
        route(
            fakeRequest("PUT", "/api/customers/" + custUuid + "/api_token")
                .header("X-AUTH-TOKEN", authToken));
    json = Json.parse(contentAsString(result));
    String apiToken1 = json.get("apiToken").asText();
    apiTokenJson.put("authToken", authToken);
    result =
        route(
            fakeRequest("PUT", "/api/customers/" + custUuid + "/api_token")
                .header("X-AUTH-TOKEN", authToken));
    json = Json.parse(contentAsString(result));
    String apiToken2 = json.get("apiToken").asText();
    assertNotEquals(apiToken1, apiToken2);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testCustomerCount() {
    startApp(false);
    Result result = route(fakeRequest("GET", "/api/customer_count"));
    JsonNode json = Json.parse(contentAsString(result));
    assertOk(result);
    assertValue(json, "count", "0");
    ModelFactory.testCustomer("Test Customer 1");
    result = route(fakeRequest("GET", "/api/customer_count"));
    json = Json.parse(contentAsString(result));
    assertOk(result);
    assertValue(json, "count", "1");
  }

  @Test
  public void testAppVersion() {
    startApp(false);
    Result result = route(fakeRequest("GET", "/api/app_version"));
    JsonNode json = Json.parse(contentAsString(result));
    assertOk(result);
    assertEquals(json, Json.newObject());
    ConfigHelper configHelper = new ConfigHelper();
    configHelper.loadConfigToDB(
        ConfigHelper.ConfigType.SoftwareVersion, ImmutableMap.of("version", "0.0.1"));
    result = route(fakeRequest("GET", "/api/app_version"));
    json = Json.parse(contentAsString(result));
    assertOk(result);
    assertValue(json, "version", "0.0.1");
  }

  @Test
  public void testProxyRequestInvalidFormat() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    Users user = ModelFactory.testUser(customer);
    String authToken = user.createAuthToken();
    Universe universe = ModelFactory.createUniverse(customer.getCustomerId());
    Result result =
        doRequestWithAuthToken(
            "GET", "/universes/" + universe.universeUUID + "/proxy/www.test.com", authToken);
    assertBadRequest(result, "Invalid proxy request");
  }

  @Test
  public void testProxyRequestInvalidIP() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    Users user = ModelFactory.testUser(customer);
    String authToken = user.createAuthToken();
    Universe universe = ModelFactory.createUniverse(customer.getCustomerId());
    Result result =
        doRequestWithAuthToken(
            "GET", "/universes/" + universe.universeUUID + "/proxy/" + "127.0.0.1:7000", authToken);
    assertBadRequest(result, "Invalid proxy request");
  }

  @Test
  public void testProxyRequestInvalidPort() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    Users user = ModelFactory.testUser(customer);
    String authToken = user.createAuthToken();
    Provider provider = ModelFactory.awsProvider(customer);

    Region r = Region.create(provider, "region-1", "PlacementRegion-1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ-1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ-2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ-3", "subnet-3");
    InstanceType i =
        InstanceType.upsert(
            provider.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());
    UniverseDefinitionTaskParams.UserIntent userIntent = getTestUserIntent(r, provider, i, 3);
    Universe universe = ModelFactory.createUniverse(customer.getCustomerId());
    Universe.saveDetails(
        universe.universeUUID, ApiUtils.mockUniverseUpdater(userIntent, "test-prefix"));
    universe = Universe.getOrBadRequest(universe.universeUUID);
    NodeDetails node = universe.getUniverseDetails().nodeDetailsSet.stream().findFirst().get();
    System.out.println("PRIVATE IP: " + node.cloudInfo.private_ip);
    Result result =
        doRequestWithAuthToken(
            "GET",
            "/universes/"
                + universe.universeUUID
                + "/proxy/"
                + node.cloudInfo.private_ip
                + ":7001/",
            authToken);
    assertBadRequest(result, "Invalid proxy request");
  }

  @Test
  public void testProxyRequestValid() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    Users user = ModelFactory.testUser(customer);
    String authToken = user.createAuthToken();
    Provider provider = ModelFactory.awsProvider(customer);

    Region r = Region.create(provider, "region-1", "PlacementRegion-1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ-1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ-2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ-3", "subnet-3");
    InstanceType i =
        InstanceType.upsert(
            provider.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());
    UniverseDefinitionTaskParams.UserIntent userIntent = getTestUserIntent(r, provider, i, 3);
    Universe universe = ModelFactory.createUniverse(customer.getCustomerId());
    Universe.saveDetails(
        universe.universeUUID, ApiUtils.mockUniverseUpdater(userIntent, "test-prefix"));
    universe = Universe.getOrBadRequest(universe.universeUUID);
    NodeDetails node = universe.getUniverseDetails().nodeDetailsSet.stream().findFirst().get();
    String nodeAddr = node.cloudInfo.private_ip + ":" + node.masterHttpPort;
    Result result =
        doRequestWithAuthToken(
            "GET", "/universes/" + universe.universeUUID + "/proxy/" + nodeAddr + "/", authToken);
    // Expect the request to fail since the hostname isn't real.
    // This shows that it got past validation though
    assertInternalServerError(
        result, "\"java.net.UnknownHostException: " + node.cloudInfo.private_ip + ":");
  }

  @Test
  public void testProxyRequestUnAuthenticated() {
    startApp(false);
    Customer customer = ModelFactory.testCustomer("Test Customer 1");
    Provider provider = ModelFactory.awsProvider(customer);

    Region r = Region.create(provider, "region-1", "PlacementRegion-1", "default-image");
    AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ-1", "subnet-1");
    AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ-2", "subnet-2");
    AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ-3", "subnet-3");
    InstanceType i =
        InstanceType.upsert(
            provider.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());
    UniverseDefinitionTaskParams.UserIntent userIntent = getTestUserIntent(r, provider, i, 3);
    Universe universe = ModelFactory.createUniverse(customer.getCustomerId());
    Universe.saveDetails(
        universe.universeUUID, ApiUtils.mockUniverseUpdater(userIntent, "test-prefix"));
    universe = Universe.getOrBadRequest(universe.universeUUID);
    NodeDetails node = universe.getUniverseDetails().nodeDetailsSet.stream().findFirst().get();
    String nodeAddr = node.cloudInfo.private_ip + ":" + node.masterHttpPort;
    Result result =
        route(
            fakeRequest("GET", "/universes/" + universe.universeUUID + "/proxy/" + nodeAddr + "/"));
    // Expect the request to fail since the hostname isn't real.
    // This shows that it got past validation though
    assertForbidden(result, "Unable To Authenticate User");
  }
}
