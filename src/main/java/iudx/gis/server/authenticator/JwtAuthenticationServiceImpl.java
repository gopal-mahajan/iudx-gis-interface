package iudx.gis.server.authenticator;

import static iudx.gis.server.authenticator.Constants.JSON_EXPIRY;
import static iudx.gis.server.authenticator.Constants.JSON_IID;
import static iudx.gis.server.authenticator.Constants.JSON_USERID;
import static iudx.gis.server.authenticator.Constants.OPEN_ENDPOINTS;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import iudx.gis.server.authenticator.authorization.Api;
import iudx.gis.server.authenticator.authorization.AuthorizationContextFactory;
import iudx.gis.server.authenticator.authorization.AuthorizationRequest;
import iudx.gis.server.authenticator.authorization.AuthorizationStrategy;
import iudx.gis.server.authenticator.authorization.IudxRole;
import iudx.gis.server.authenticator.authorization.JwtAuthorization;
import iudx.gis.server.authenticator.authorization.Method;
import iudx.gis.server.authenticator.model.JwtData;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JwtAuthenticationServiceImpl implements AuthenticationService {

  private static final Logger LOGGER = LogManager.getLogger(JwtAuthenticationServiceImpl.class);

  final JWTAuth jwtAuth;
  final WebClient catWebClient;
  final String host;
  final int port;
  final String path;
  final String audience;
  final String iss;

  // resourceGroupCache will contains ACL info about all resource group in a resource server
  Cache<String, String> resourceGroupCache =
      CacheBuilder.newBuilder()
          .maximumSize(1000)
          .expireAfterAccess(Constants.CACHE_TIMEOUT_AMOUNT, TimeUnit.MINUTES)
          .build();
  // resourceIdCache will contains info about resources available(& their ACL) in resource server.
  Cache<String, String> resourceIdCache =
      CacheBuilder.newBuilder()
          .maximumSize(1000)
          .expireAfterAccess(Constants.CACHE_TIMEOUT_AMOUNT, TimeUnit.MINUTES)
          .build();

  JwtAuthenticationServiceImpl(Vertx vertx, final JWTAuth jwtAuth, final JsonObject config) {
    this.jwtAuth = jwtAuth;
    this.audience = config.getString("host");
    this.iss = config.getString("authServerHost");
    this.host = config.getString("catServerHost");
    this.port = config.getInteger("catServerPort");
    this.path = Constants.CAT_RSG_PATH;

    WebClientOptions options = new WebClientOptions();
    options.setTrustAll(true).setVerifyHost(false).setSsl(true);
    catWebClient = WebClient.create(vertx, options);
  }

  @Override
  public AuthenticationService tokenIntrospect(
      JsonObject request, JsonObject authenticationInfo, Handler<AsyncResult<JsonObject>> handler) {

    String id = authenticationInfo.getString("id");
    String token = authenticationInfo.getString("token");

    Future<JwtData> jwtDecodeFuture = decodeJwt(token);

    ResultContainer result = new ResultContainer();

    jwtDecodeFuture
        .compose(
            decodeHandler -> {
              result.jwtData = decodeHandler;
              LOGGER.info(result.jwtData);
              return isValidAudienceValue(result.jwtData);
            })
        .compose(audienceHandler -> isValidIssuerValue(result.jwtData))
        .compose(
            issuerHandler -> {
              if (!result.jwtData.getIss().equals(result.jwtData.getSub())) {
                return isOpenResource(id);
              } else {
                return Future.succeededFuture("OPEN");
              }
            })
        .compose(
            openResourceHandler -> {
              result.isOpen = openResourceHandler.equalsIgnoreCase("OPEN");
              if (result.isOpen) {
                JsonObject json = new JsonObject();
                json.put(JSON_USERID, result.jwtData.getSub());
                return Future.succeededFuture(true);
              } else {
                return isValidId(result.jwtData, id);
              }
            })
        .compose(
            validIdHandler -> validateAccess(result.jwtData, result.isOpen, authenticationInfo))
        .onSuccess(successHandler -> handler.handle(Future.succeededFuture(successHandler)))
        .onFailure(
            failureHandler -> handler.handle(Future.failedFuture(failureHandler.getMessage())));
    return this;
  }

  Future<JwtData> decodeJwt(String jwtToken) {
    Promise<JwtData> promise = Promise.promise();
    TokenCredentials creds = new TokenCredentials(jwtToken);

    jwtAuth
        .authenticate(creds)
        .onSuccess(
            user -> {
              JwtData jwtData = new JwtData(user.principal());
              jwtData.setExp(user.get("exp"));
              promise.complete(jwtData);
            })
        .onFailure(
            err -> {
              LOGGER.error("failed to decode/validate jwt token : " + err.getMessage());
              promise.fail("failed");
            });

    return promise.future();
  }

  private Future<String> isOpenResource(String id) {
    LOGGER.debug("isOpenResource() started");
    Promise<String> promise = Promise.promise();

    String ACL = resourceIdCache.getIfPresent(id);
    if (ACL != null) {
      LOGGER.debug("Cache Hit");
      promise.complete(ACL);
    } else {
      // cache miss
      LOGGER.debug("Cache miss calling cat server");
      String[] idComponents = id.split("/");
      if (idComponents.length < 4) {
        promise.fail("Not Found " + id);
      }
      String groupId =
          (idComponents.length == 4)
              ? id
              : String.join("/", Arrays.copyOfRange(idComponents, 0, 4));
      // 1. check group accessPolicy.
      // 2. check resource exist, if exist set accessPolicy to group accessPolicy. else fail
      Future<String> groupACLFuture = getGroupAccessPolicy(groupId);
      groupACLFuture
          .compose(
              groupACLResult -> {
                String groupPolicy = groupACLResult;
                return isResourceExist(id, groupPolicy);
              })
          .onSuccess(
              handler -> {
                promise.complete(resourceIdCache.getIfPresent(id));
              })
          .onFailure(
              handler -> {
                LOGGER.error("cat response failed for Id : (" + id + ")" + handler.getCause());
                promise.fail("Not Found " + id);
              });
    }
    return promise.future();
  }

  public Future<JsonObject> validateAccess(
      JwtData jwtData, boolean openResource, JsonObject authInfo) {
    LOGGER.trace("validateAccess() started");
    Promise<JsonObject> promise = Promise.promise();
    String jwtId = jwtData.getIid().split(":")[1];

    if (jwtData.getRole().equals("consumer")) {

      if (openResource && OPEN_ENDPOINTS.contains(authInfo.getString("apiEndpoint"))) {
        LOGGER.info("IS OPEN");
        LOGGER.info("User access is allowed.");
        JsonObject jsonResponse = new JsonObject();
        jsonResponse.put(JSON_IID, jwtId);
        jsonResponse.put(JSON_USERID, jwtData.getSub());
        return Future.succeededFuture(jsonResponse);
      }

      Method method = Method.valueOf(authInfo.getString("method"));
      Api api = Api.fromEndpoint(authInfo.getString("apiEndpoint"));
      AuthorizationRequest authRequest = new AuthorizationRequest(method, api);

      IudxRole role = IudxRole.fromRole(jwtData.getRole());
      AuthorizationStrategy authStrategy = AuthorizationContextFactory.create(role);
      LOGGER.info("strategy : " + authStrategy.getClass().getSimpleName());
      JwtAuthorization jwtAuthStrategy = new JwtAuthorization(authStrategy);
      LOGGER.info("endPoint : " + authInfo.getString("apiEndpoint"));
      if (jwtAuthStrategy.isAuthorized(authRequest, jwtData)) {
        LOGGER.info("User access is allowed.");
        JsonObject jsonResponse = new JsonObject();
        jsonResponse.put(JSON_USERID, jwtData.getSub());
        jsonResponse.put(JSON_IID, jwtId);
        LOGGER.info("jwt : " + jwtData);
        jsonResponse.put(
            JSON_EXPIRY,
            (LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(Long.parseLong(jwtData.getExp().toString())),
                    ZoneId.systemDefault()))
                .toString());
        promise.complete(jsonResponse);
      } else {
        LOGGER.info("failed");
        JsonObject result = new JsonObject().put("401", "no access provided to endpoint");
        promise.fail(result.toString());
      }
    } else {
      LOGGER.info("failed");
      JsonObject result = new JsonObject().put("401", "only consumer access allowed.");
      promise.fail(result.toString());
    }
    return promise.future();
  }

  Future<Boolean> isValidAudienceValue(JwtData jwtData) {
    Promise<Boolean> promise = Promise.promise();

    if (audience != null && audience.equalsIgnoreCase(jwtData.getAud())) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect audience value in jwt");
      promise.fail("Incorrect audience value in jwt");
    }
    return promise.future();
  }

  Future<Boolean> isValidExpiryTime(JwtData jwtData) {
    Promise<Boolean> promise = Promise.promise();

    if (LocalDateTime.ofInstant(
            Instant.ofEpochSecond(Long.parseLong(jwtData.getExp().toString())),
            ZoneId.systemDefault())
        .isAfter(LocalDateTime.now())) {
      promise.complete(true);
    } else {
      LOGGER.error("Token Expired.");
      promise.fail("Token Expired.");
    }
    return promise.future();
  }

  Future<Boolean> isValidIssuerValue(JwtData jwtData) {
    Promise<Boolean> promise = Promise.promise();
    if (iss != null && iss.equalsIgnoreCase(jwtData.getIss())) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect iss value in jwt");
      promise.fail("Incorrect iss value in jwt");
    }
    return promise.future();
  }

  Future<Boolean> isValidId(JwtData jwtData, String id) {
    LOGGER.debug("Is Valid Started. ");
    Promise<Boolean> promise = Promise.promise();
    String jwtId = jwtData.getIid().split(":")[1];
    if (id.equalsIgnoreCase(jwtId)) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect id value in jwt");
      promise.fail("Incorrect id value in jwt");
    }

    return promise.future();
  }

  private Future<Boolean> isResourceExist(String id, String groupACL) {
    LOGGER.debug("isResourceExist() started");
    Promise<Boolean> promise = Promise.promise();
    String resourceExist = resourceIdCache.getIfPresent(id);
    if (resourceExist != null) {
      LOGGER.debug("Info : cache Hit");
      promise.complete(true);
    } else {
      LOGGER.debug("Info : Cache miss : call cat server");
      catWebClient
          .get(port, host, path)
          .addQueryParam("property", "[id]")
          .addQueryParam("value", "[[" + id + "]]")
          .addQueryParam("filter", "[id]")
          .expect(ResponsePredicate.JSON)
          .send(
              responseHandler -> {
                if (responseHandler.failed()) {
                  promise.fail("false");
                }
                HttpResponse<Buffer> response = responseHandler.result();
                JsonObject responseBody = response.bodyAsJsonObject();
                if (response.statusCode() != HttpStatus.SC_OK) {
                  promise.fail("false");
                } else if (!responseBody.getString("type").equals("urn:dx:cat:Success")) {
                  promise.fail("Not Found");
                  return;
                } else if (responseBody.getInteger("totalHits") == 0) {
                  LOGGER.debug("Info: Resource ID invalid : Catalogue item Not Found");
                  promise.fail("Not Found");
                } else {
                  LOGGER.debug("is Exist response : " + responseBody);
                  resourceIdCache.put(id, groupACL);
                  promise.complete(true);
                }
              });
    }
    return promise.future();
  }

  private Future<String> getGroupAccessPolicy(String groupId) {
    LOGGER.debug("getGroupAccessPolicy() started");
    Promise<String> promise = Promise.promise();
    String groupACL = resourceGroupCache.getIfPresent(groupId);
    if (groupACL != null) {
      LOGGER.debug("Info : cache Hit");
      promise.complete(groupACL);
    } else {
      LOGGER.debug("Info : cache miss");
      catWebClient
          .get(port, host, path)
          .addQueryParam("property", "[id]")
          .addQueryParam("value", "[[" + groupId + "]]")
          .addQueryParam("filter", "[accessPolicy]")
          .expect(ResponsePredicate.JSON)
          .send(
              httpResponseAsyncResult -> {
                if (httpResponseAsyncResult.failed()) {
                  LOGGER.error(httpResponseAsyncResult.cause());
                  promise.fail("Resource not found");
                  return;
                }
                HttpResponse<Buffer> response = httpResponseAsyncResult.result();
                if (response.statusCode() != HttpStatus.SC_OK) {
                  promise.fail("Resource not found");
                  return;
                }
                JsonObject responseBody = response.bodyAsJsonObject();
                if (!responseBody.getString("type").equals("urn:dx:cat:Success")) {
                  promise.fail("Resource not found");
                  return;
                }
                String resourceACL = "SECURE";
                try {
                  resourceACL =
                      responseBody
                          .getJsonArray("results")
                          .getJsonObject(0)
                          .getString("accessPolicy");
                  resourceGroupCache.put(groupId, resourceACL);
                  LOGGER.debug("Info: Group ID valid : Catalogue item Found");
                  promise.complete(resourceACL);
                } catch (Exception ignored) {
                  LOGGER.error(ignored.getMessage());
                  LOGGER.debug("Info: Group ID invalid : Empty response in results from Catalogue");
                  promise.fail("Resource not found");
                }
              });
    }
    return promise.future();
  }

  // class to contain intermediate data for token interospection
  final class ResultContainer {
    JwtData jwtData;
    boolean isOpen;
  }
}