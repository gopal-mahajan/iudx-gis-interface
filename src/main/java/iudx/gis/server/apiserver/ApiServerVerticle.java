package iudx.gis.server.apiserver;

import static iudx.gis.server.apiserver.response.ResponseUrn.BACKING_SERVICE_FORMAT;
import static iudx.gis.server.apiserver.response.ResponseUrn.INVALID_ATTR_PARAM;
import static iudx.gis.server.apiserver.response.ResponseUrn.METHOD_NOT_FOUND;
import static iudx.gis.server.apiserver.response.ResponseUrn.SUCCESS;
import static iudx.gis.server.apiserver.response.ResponseUrn.YET_NOT_IMPLEMENTED;
import static iudx.gis.server.apiserver.util.Constants.ADMIN_BASE_PATH;
import static iudx.gis.server.apiserver.util.Constants.API;
import static iudx.gis.server.apiserver.util.Constants.API_ENDPOINT;
import static iudx.gis.server.apiserver.util.Constants.APPLICATION_JSON;
import static iudx.gis.server.apiserver.util.Constants.CONTENT_TYPE;
import static iudx.gis.server.apiserver.util.Constants.ERROR_MESSAGE;
import static iudx.gis.server.apiserver.util.Constants.HEADER_ACCEPT;
import static iudx.gis.server.apiserver.util.Constants.HEADER_ALLOW_ORIGIN;
import static iudx.gis.server.apiserver.util.Constants.HEADER_CONTENT_LENGTH;
import static iudx.gis.server.apiserver.util.Constants.HEADER_CONTENT_TYPE;
import static iudx.gis.server.apiserver.util.Constants.HEADER_HOST;
import static iudx.gis.server.apiserver.util.Constants.HEADER_ORIGIN;
import static iudx.gis.server.apiserver.util.Constants.HEADER_REFERER;
import static iudx.gis.server.apiserver.util.Constants.HEADER_TOKEN;
import static iudx.gis.server.apiserver.util.Constants.ID;
import static iudx.gis.server.apiserver.util.Constants.JSON_DETAIL;
import static iudx.gis.server.apiserver.util.Constants.JSON_DOMAIN;
import static iudx.gis.server.apiserver.util.Constants.JSON_ID;
import static iudx.gis.server.apiserver.util.Constants.JSON_INSTANCEID;
import static iudx.gis.server.apiserver.util.Constants.JSON_RESOURCE_GROUP;
import static iudx.gis.server.apiserver.util.Constants.JSON_RESOURCE_NAME;
import static iudx.gis.server.apiserver.util.Constants.JSON_RESOURCE_SERVER;
import static iudx.gis.server.apiserver.util.Constants.JSON_TITLE;
import static iudx.gis.server.apiserver.util.Constants.JSON_TYPE;
import static iudx.gis.server.apiserver.util.Constants.JSON_USERSHA;
import static iudx.gis.server.apiserver.util.Constants.MIME_APPLICATION_JSON;
import static iudx.gis.server.apiserver.util.Constants.NGSILDQUERY_ID;
import static iudx.gis.server.apiserver.util.Constants.NGSILDQUERY_IDPATTERN;
import static iudx.gis.server.apiserver.util.Constants.NGSILD_ENTITIES_URL;
import static iudx.gis.server.apiserver.util.Constants.USER_ID;

import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import iudx.gis.server.apiserver.handlers.AuthHandler;
import iudx.gis.server.apiserver.handlers.ValidationFailureHandler;
import iudx.gis.server.apiserver.handlers.ValidationHandler;
import iudx.gis.server.apiserver.response.ResponseType;
import iudx.gis.server.apiserver.response.ResponseUrn;
import iudx.gis.server.apiserver.service.CatalogueService;
import iudx.gis.server.apiserver.util.HttpStatusCode;
import iudx.gis.server.apiserver.util.RequestType;
import iudx.gis.server.authenticator.AuthenticationService;
import iudx.gis.server.database.DatabaseService;
import iudx.gis.server.metering.MeteringService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ApiServerVerticle extends AbstractVerticle {
  public static final String METERING_SERVICE_ADDRESS = "iudx.gis.metering.service";
  private static final Logger LOGGER = LogManager.getLogger(ApiServerVerticle.class);
  /** Service addresses */
  private static final String DATABASE_SERVICE_ADDRESS = "iudx.gis.database.service";

  private static final String AUTH_SERVICE_ADDRESS = "iudx.gis.authentication.service";
  // private ParamsValidator validator;
  private static final Set<String> validParams = new HashSet<String>();
  private static final Set<String> validHeaders = new HashSet<String>();

  static {
    validParams.add(NGSILDQUERY_ID);
    validParams.add(NGSILDQUERY_IDPATTERN);
    validHeaders.add(HEADER_TOKEN);
    validHeaders.add("User-Agent");
    validHeaders.add("Content-Type");
  }

  private HttpServer server;
  private Router router;
  private int port = 11111;
  private boolean isSSL, isProduction;
  private String keystore;
  private String keystorePassword;
  private CatalogueService catalogueService;
  private MeteringService meteringService;
  private DatabaseService database;
  private AuthenticationService authenticator;

  @Override
  public void start() throws Exception {
    Set<String> allowedHeaders = new HashSet<>();
    allowedHeaders.add(HEADER_ACCEPT);
    allowedHeaders.add(HEADER_TOKEN);
    allowedHeaders.add(HEADER_CONTENT_LENGTH);
    allowedHeaders.add(HEADER_CONTENT_TYPE);
    allowedHeaders.add(HEADER_HOST);
    allowedHeaders.add(HEADER_ORIGIN);
    allowedHeaders.add(HEADER_REFERER);
    allowedHeaders.add(HEADER_ALLOW_ORIGIN);

    Set<HttpMethod> allowedMethods = new HashSet<>();
    allowedMethods.add(HttpMethod.GET);
    allowedMethods.add(HttpMethod.POST);
    allowedMethods.add(HttpMethod.PATCH);
    allowedMethods.add(HttpMethod.PUT);

    router = Router.router(vertx);
    router
        .route()
        .handler(
            CorsHandler.create("*").allowedHeaders(allowedHeaders).allowedMethods(allowedMethods));

    router
        .route()
        .handler(
            requestHandler -> {
              requestHandler
                  .response()
                  .putHeader("Cache-Control", "no-cache, no-store,  must-revalidate,max-age=0")
                  .putHeader("Pragma", "no-cache")
                  .putHeader("Expires", "0")
                  .putHeader("X-Content-Type-Options", "nosniff");
              requestHandler.next();
            });

    router.route().handler(BodyHandler.create());

    /* Read ssl configuration. */
    isSSL = config().getBoolean("ssl");

    /* Read server deployment configuration. */
    isProduction = config().getBoolean("production");

    HttpServerOptions serverOptions = new HttpServerOptions();

    if (isSSL) {
      LOGGER.debug("Info: Starting HTTPs server");

      /* Read the configuration and set the HTTPs server properties. */

      keystore = config().getString("keystore");
      keystorePassword = config().getString("keystorePassword");

      /* Setup the HTTPs server properties, APIs and port. */

      serverOptions
          .setSsl(true)
          .setKeyStoreOptions(new JksOptions().setPath(keystore).setPassword(keystorePassword));

    } else {
      LOGGER.debug("Info: Starting HTTP server");

      /* Setup the HTTP server properties, APIs and port. */

      serverOptions.setSsl(false);
      if (isProduction) {
        port = 80;
      } else {
        port = 8080;
      }
    }

    serverOptions.setCompressionSupported(true).setCompressionLevel(5);
    server = vertx.createHttpServer(serverOptions);
    server.requestHandler(router).listen(port);

    /* Get a handler for the Service Discovery interface. */

    database = DatabaseService.createProxy(vertx, DATABASE_SERVICE_ADDRESS);
    authenticator = AuthenticationService.createProxy(vertx, AUTH_SERVICE_ADDRESS);
    meteringService = MeteringService.createProxy(vertx, METERING_SERVICE_ADDRESS);

    ValidationHandler entityQueryValidationHandler =
        new ValidationHandler(vertx, RequestType.ENTITY_QUERY);
    ValidationFailureHandler validationsFailureHandler = new ValidationFailureHandler();

    router
        .get(NGSILD_ENTITIES_URL)
        .handler(entityQueryValidationHandler)
        .handler(AuthHandler.create(vertx))
        .handler(this::handleEntitiesQuery)
        .failureHandler(validationsFailureHandler);

    ValidationHandler entityPathValidationHandler =
        new ValidationHandler(vertx, RequestType.ENTITY_PATH);
    router
        .get(NGSILD_ENTITIES_URL + "/:domain/:userSha/:resourceServer/:resourceGroup/:resourceName")
        .handler(entityPathValidationHandler)
        .handler(this::handleEntitiesPath)
        .failureHandler(validationsFailureHandler);

    ValidationHandler adminCrudPathValidationHandler =
        new ValidationHandler(vertx, RequestType.ADMIN_CRUD_PATH);

    ValidationHandler adminCrudPathIdValidationHandler =
        new ValidationHandler(vertx, RequestType.ADMIN_CRUD_PATH_DELETE);

    router.get(ADMIN_BASE_PATH).handler(this::handleGetAdminPath);

    router
        .post(ADMIN_BASE_PATH)
        .handler(adminCrudPathValidationHandler)
        .handler(this::handlePostAdminPath)
        .failureHandler(validationsFailureHandler);

    router
        .put(ADMIN_BASE_PATH)
        .handler(adminCrudPathValidationHandler)
        .handler(this::handlePutAdminPath)
        .failureHandler(validationsFailureHandler);

    router
        .delete(ADMIN_BASE_PATH)
        .handler(adminCrudPathIdValidationHandler)
        .handler(this::handleDeleteAdminPath)
        .failureHandler(validationsFailureHandler);

    router
        .route()
        .last()
        .handler(
            requestHandler -> {
              HttpServerResponse response = requestHandler.response();
              response
                  .putHeader(HEADER_CONTENT_TYPE, MIME_APPLICATION_JSON)
                  .setStatusCode(404)
                  .end(generateResponse(HttpStatusCode.NOT_FOUND, YET_NOT_IMPLEMENTED));
            });

    catalogueService = new CatalogueService(vertx, config());
  }

  private void handleGetAdminPath(RoutingContext routingContext) {
    handleResponse(routingContext.response(), HttpStatusCode.METHOD_NOT_ALLOWED, METHOD_NOT_FOUND);
  }

  private void handleDeleteAdminPath(RoutingContext routingContext) {
    LOGGER.debug("Info:handleDeleteAdminPath method started.;");
    HttpServerResponse response = routingContext.response();
    String resourceId = routingContext.queryParams().get(ID);

    database.deleteAdminDetails(
        resourceId,
        ar -> {
          if (ar.succeeded()) {
            LOGGER.debug("Success: Delete operation successful");
            handleSuccessResponse(response, ResponseType.Ok.getCode(), ar.result().toString());
          } else {
            LOGGER.error("Fail: Delete operation Failed");
            processBackendResponse(response, ar.cause().getMessage());
          }
        });
  }

  private void handlePutAdminPath(RoutingContext routingContext) {
    LOGGER.debug("Info:handlePutAdminPath method started.;");
    HttpServerResponse response = routingContext.response();

    JsonObject requestBody = routingContext.getBodyAsJson();

    database.updateAdminDetails(
        requestBody,
        ar -> {
          if (ar.succeeded()) {
            LOGGER.debug("Success: Update operation successful");
            handleSuccessResponse(response, ResponseType.Ok.getCode(), ar.result().toString());
          } else {
            LOGGER.error("Fail: Update operation Failed");
            processBackendResponse(response, ar.cause().getMessage());
          }
        });
  }

  private void handlePostAdminPath(RoutingContext routingContext) {
    LOGGER.debug("Info:handlePostAdminPath method started.;");
    HttpServerResponse response = routingContext.response();

    JsonObject requestBody = routingContext.getBodyAsJson();

    database.insertAdminDetails(
        requestBody,
        ar -> {
          if (ar.succeeded()) {
            LOGGER.debug("Success: Insert operation successful");
            handleSuccessResponse(response, ResponseType.Ok.getCode(), ar.result().toString());
          } else {
            LOGGER.error("Fail: Insert operation Failed");
            processBackendResponse(response, ar.cause().getMessage());
          }
        });
  }

  private void handleEntitiesPath(RoutingContext routingContext) {
    LOGGER.debug("Info:handleLatestEntitiesQuery method started.;");
    /* Handles HTTP request from client */
    JsonObject authInfo = (JsonObject) routingContext.data().get("authInfo");
    LOGGER.debug("authInfo : " + authInfo);
    HttpServerRequest request = routingContext.request();
    /* Handles HTTP response from server to client */
    HttpServerResponse response = routingContext.response();

    String domain = request.getParam(JSON_DOMAIN);
    String userSha = request.getParam(JSON_USERSHA);
    String resourceServer = request.getParam(JSON_RESOURCE_SERVER);
    String resourceGroup = request.getParam(JSON_RESOURCE_GROUP);
    String resourceName = request.getParam(JSON_RESOURCE_NAME);
    String id =
        domain + "/" + userSha + "/" + resourceServer + "/" + resourceGroup + "/" + resourceName;
    JsonObject json = new JsonObject();
    /* HTTP request instance/host details */
    String instanceID = request.getHeader(HEADER_HOST);
    json.put(JSON_INSTANCEID, instanceID);
    json.put(JSON_ID, id);
    LOGGER.debug("Info: IUDX query json;" + json);
    // check Catalogue Cache before search
    Future<Boolean> isIdPresent = catalogueService.isItemExist(id);
    isIdPresent
        .onSuccess(handler -> executeSearchQuery(routingContext, json, response))
        .onFailure(handler -> processBackendResponse(response, handler.getCause().getMessage()));
  }

  private void handleEntitiesQuery(RoutingContext routingContext) {
    LOGGER.debug("Info:handleEntitiesQuery method started.;");
    /* Handles HTTP request from client */
    JsonObject authInfo = (JsonObject) routingContext.data().get("authInfo");
    LOGGER.debug("authInfo : " + authInfo);
    HttpServerRequest request = routingContext.request();
    /* Handles HTTP response from server to client */
    HttpServerResponse response = routingContext.response();
    // get query paramaters
    String id = request.getParam(ID);
    JsonObject json = new JsonObject();
    json.put(ID, id);
    Future<Boolean> isIdPresent = catalogueService.isItemExist(id);
    isIdPresent
        .onSuccess(handler -> executeSearchQuery(routingContext, json, response))
        .onFailure(handler -> processBackendResponse(response, handler.getCause().getMessage()));
  }

  /**
   * Execute a search query in DB
   *
   * @param json valid json query
   * @param response
   */
  private void executeSearchQuery(
      RoutingContext context, JsonObject json, HttpServerResponse response) {
    database.searchQuery(
        json,
        handler -> {
          if (handler.succeeded()) {
            LOGGER.info("Success: Search Success");
            Future.future(fu -> updateAuditTable(context));
            handleSuccessResponse(response, ResponseType.Ok.getCode(), handler.result().toString());
            LOGGER.info("CONTEXT " + context);
          } else if (handler.failed()) {
            LOGGER.error("Fail: Search Fail");
            processBackendResponse(response, handler.cause().getMessage());
          }
        });
  }

  private void handleSuccessResponse(HttpServerResponse response, int statusCode, String result) {
    response
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .setStatusCode(statusCode)
        .end(generateResponse(HttpStatusCode.SUCCESS, SUCCESS, result));
  }

  private void processBackendResponse(HttpServerResponse response, String failureMessage) {
    LOGGER.debug("Info : " + failureMessage);
    try {
      JsonObject json = new JsonObject(failureMessage);
      int type = json.getInteger(JSON_TYPE);
      HttpStatusCode status = HttpStatusCode.getByValue(type);
      String urnTitle = json.getString(JSON_TITLE);
      ResponseUrn urn;
      if (urnTitle != null) {
        urn = ResponseUrn.fromCode(urnTitle);
      } else {
        urn = ResponseUrn.fromCode(type + "");
      }
      String errorMessage = json.getString(ERROR_MESSAGE);
      response.putHeader(CONTENT_TYPE, APPLICATION_JSON).setStatusCode(type);
      if (errorMessage == null || errorMessage.isEmpty()) {
        response.end(generateResponse(status, urn));
      } else {
        response.end(generateResponse(status, urn, errorMessage));
      }
    } catch (DecodeException ex) {
      LOGGER.error("ERROR : Expecting Json from backend service [ jsonFormattingException ]");
      handleResponse(response, HttpStatusCode.BAD_REQUEST, BACKING_SERVICE_FORMAT);
    }
  }

  private void handleResponse(HttpServerResponse response, HttpStatusCode code, ResponseUrn urn) {
    handleResponse(response, code, urn, code.getDescription());
  }

  private void handleResponse(
      HttpServerResponse response, HttpStatusCode statusCode, ResponseUrn urn, String message) {
    response
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .setStatusCode(statusCode.getValue())
        .end(generateResponse(statusCode, urn, message));
  }

  private String generateResponse(HttpStatusCode statusCode, ResponseUrn urn) {
    return generateResponse(statusCode, urn, statusCode.getDescription());
  }

  private String generateResponse(HttpStatusCode statusCode, ResponseUrn urn, String message) {
    return new JsonObject()
        .put(JSON_TYPE, urn.getUrn())
        .put(JSON_TITLE, statusCode.getDescription())
        .put(JSON_DETAIL, message)
        .toString();
  }

  private Optional<MultiMap> getQueryParams(
      RoutingContext routingContext, HttpServerResponse response) {
    MultiMap queryParams = null;
    try {
      queryParams = MultiMap.caseInsensitiveMultiMap();
      String uri = routingContext.request().uri();
      Map<String, List<String>> decodedParams =
          new QueryStringDecoder(uri, HttpConstants.DEFAULT_CHARSET, true, 1024, true).parameters();
      for (Map.Entry<String, List<String>> entry : decodedParams.entrySet()) {
        LOGGER.debug("Info: param :" + entry.getKey() + " value : " + entry.getValue());
        queryParams.add(entry.getKey(), entry.getValue());
      }
    } catch (IllegalArgumentException ex) {
      response
          .putHeader(CONTENT_TYPE, APPLICATION_JSON)
          .setStatusCode(ResponseType.BadRequestData.getCode())
          .end(generateResponse(HttpStatusCode.BAD_REQUEST, INVALID_ATTR_PARAM));
    }
    return Optional.of(queryParams);
  }

  private boolean validateParams(MultiMap parameterMap) {
    final List<Map.Entry<String, String>> entries = parameterMap.entries();
    for (final Map.Entry<String, String> entry : entries) {
      // System.out.println(entry.getKey());
      if (!validParams.contains(entry.getKey())) {
        return false;
      }
    }
    return true;
  }

  private Future<Void> updateAuditTable(RoutingContext context) {
    Promise<Void> promise = Promise.promise();
    JsonObject authInfo = (JsonObject) context.data().get("authInfo");

    JsonObject request = new JsonObject();
    request.put(USER_ID, authInfo.getValue(USER_ID));
    request.put(ID, authInfo.getValue(ID));
    request.put(API, authInfo.getValue(API_ENDPOINT));
    meteringService.executeWriteQuery(
        request,
        handler -> {
          if (handler.succeeded()) {
            LOGGER.info("audit table updated");
            promise.complete();
          } else {
            LOGGER.error("failed to update audit table");
            promise.complete();
          }
        });

    return promise.future();
  }
}
