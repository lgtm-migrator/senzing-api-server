package com.senzing.api.services;

import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.senzing.api.model.*;
import com.senzing.api.server.SzApiServer;
import com.senzing.g2.engine.G2Engine;
import com.senzing.g2.engine.G2JNI;
import com.senzing.repomgr.RepositoryManager;
import com.senzing.util.AccessToken;
import com.senzing.util.JsonUtils;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.UriInfo;

import static com.senzing.api.BuildInfo.MAVEN_VERSION;
import static com.senzing.api.model.SzHttpMethod.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Provides an abstract base class for services tests that will create a
 * Senzing repository and startup the API server configured to use that
 * repository.  It also provides hooks to load the repository with data.
 */
public abstract class AbstractServiceTest {
  /**
   * Whether or not the Senzing native API is available and the G2 native
   * library could be loaded.
   */
  protected static final boolean NATIVE_API_AVAILABLE;

  /**
   * Message to display when the Senzing API is not available and the tests
   * are being skipped.
   */
  protected static final String NATIVE_API_UNAVAILABLE_MESSAGE;

  static {
    G2Engine engineApi = null;
    StringWriter sw = new StringWriter();
    try {
      PrintWriter pw = new PrintWriter(sw);
      pw.println();
      pw.println("Skipping Tests: The Senzing Native API is NOT available.");
      pw.println("Check that SENZING_DIR environment variable is properly defined.");
      pw.println("Alternatively, you can run maven (mvn) with -Dsenzing.dir=[path].");
      pw.println();

      try {
        engineApi = new G2JNI();
      } catch (Throwable ignore) {
        // do nothing
      }
    } finally {
      NATIVE_API_AVAILABLE = (engineApi != null);
      NATIVE_API_UNAVAILABLE_MESSAGE = sw.toString();
    }
  }

  /**
   * The API Server being used to run the tests.
   */
  private SzApiServer server;

  /**
   * The repository directory used to run the tests.
   */
  private File repoDirectory;

  /**
   * The access token to use for privileged access to created objects.
   */
  private AccessToken accessToken;

  /**
   * The access token to use to unregister the API provider.
   */
  private AccessToken providerToken;

  /**
   * Whether or not the repository has been created.
   */
  private boolean repoCreated = false;

  /**
   * Creates a temp repository directory.
   *
   * @return The {@link File} representing the directory.
   */
  private static File createTempDirectory() {
    try {
      return Files.createTempDirectory("sz-repo-").toFile();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Protected default constructor.
   */
  protected AbstractServiceTest() {
    this(createTempDirectory());
  }

  /**
   * Protected constructor allowing the derived class to specify the
   * location for the entity respository.
   *
   * @param repoDirectory The directory in which to include the entity
   *                      repository.
   */
  protected AbstractServiceTest(File repoDirectory) {
    this.server = null;
    this.repoDirectory = repoDirectory;
    this.accessToken = new AccessToken();
    this.providerToken = null;
  }

  /**
   * Creates an absolute URI for the relative URI provided.  For example, if
   * <tt>"license"</tt> was passed as the parameter then
   * <tt>"http://localhost:[port]/license"</tt> will be returned where
   * <tt>"[port]"</tt> is the port number of the currently running server, if
   * running, and is <tt>"2080"</tt> (the default port) if not running.
   *
   * @param relativeUri The relative URI to build the absolute URI from.
   * @return The absolute URI for localhost on the current port.
   */
  protected String formatServerUri(String relativeUri) {
    StringBuilder sb = new StringBuilder();
    sb.append("http://localhost:");
    if (this.server != null) {
      sb.append(this.server.getHttpPort());
    } else {
      sb.append("2080");
    }
    if (relativeUri.startsWith(sb.toString())) return relativeUri;
    sb.append("/" + relativeUri);
    return sb.toString();
  }

  /**
   * Checks that the Senzing Native API is available and if not causes the
   * test or tests to be skipped.
   *
   * @return <tt>true</tt> if the native API's are available, otherwise
   * <tt>false</tt>
   */
  protected boolean assumeNativeApiAvailable() {
    assumeTrue(NATIVE_API_AVAILABLE, NATIVE_API_UNAVAILABLE_MESSAGE);
    return NATIVE_API_AVAILABLE;
  }

  /**
   * This method can typically be called from a method annotated with
   * "@BeforeClass".  It will create a Senzing entity repository and
   * initialize and start the Senzing API Server.
   */
  protected void initializeTestEnvironment() {
    if (!NATIVE_API_AVAILABLE) return;
    RepositoryManager.createRepo(this.getRepositoryDirectory(), true);
    this.repoCreated = true;
    this.prepareRepository();
    RepositoryManager.conclude();
    this.initializeServer();
  }

  /**
   * This method can typically be called from a method annotated with
   * "@AfterClass".  It will shutdown the server and optionally delete
   * the entity repository that was created for the tests.
   *
   * @param deleteRepository <tt>true</tt> if the test repository should be
   *                         deleted, otherwise <tt>false</tt>
   */
  protected void teardownTestEnvironment(boolean deleteRepository) {
    // destroy the server
    if (this.server != null) this.destroyServer();

    // cleanup the repo directory
    if (this.repoCreated && deleteRepository
        && this.repoDirectory.exists() && this.repoDirectory.isDirectory()) {
      try {
        // delete the repository
        Files.walk(this.repoDirectory.toPath())
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);

      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    // for good measure
    if (NATIVE_API_AVAILABLE) {
      RepositoryManager.conclude();
    }
  }

  /**
   * Returns the {@link File} identifying the repository directory used for
   * the test.  This can be specified in the constructor, but if not specified
   * is a newly created temporary directory.
   */
  protected File getRepositoryDirectory() {
    return this.repoDirectory;
  }

  /**
   * Override this function to prepare the repository by configuring
   * data sources or loading records.  By default this function does nothing.
   * The repository directory can be obtained via {@link
   * #getRepositoryDirectory()}.
   */
  protected void prepareRepository() {
    // do nothing
  }

  /**
   * Stops the server if it is running and purges the repository.  After
   * purging the server is restarted.
   */
  protected void purgeRepository() {
    this.purgeRepository(true);
  }

  /**
   * Stops the server if it is running and purges the repository.  After
   * purging the server is <b>optionally</b> restarted.  You may not want to
   * restart the server if you intend to load more records into via the
   * {@link RepositoryManager} before restarting.
   *
   * @param restartServer <tt>true</tt> to restart the server and <tt>false</tt>
   *                      if you intend to restart it manually.
   * @see #restartServer()
   */
  protected void purgeRepository(boolean restartServer) {
    boolean running = (this.server != null);
    if (running) this.destroyServer();
    RepositoryManager.purgeRepo(this.repoDirectory);
    if (running && restartServer) this.initializeServer();
  }

  /**
   * Restarts the server.  If the server is already running it is shutdown
   * first and then started.  If not running it is started up.  This cannot
   * be called prior to the repository being created.
   */
  protected void restartServer() {
    if (!this.repoCreated) {
      throw new IllegalStateException(
          "Cannnot restart server prior to calling initializeTestEnvironment()");
    }
    RepositoryManager.conclude();
    this.destroyServer();
    this.initializeServer();
  }

  /**
   * Internal method for shutting down and destroying the server.  This method
   * has no effect if the server is not currently initialized.
   */
  private void destroyServer() {
    if (this.server == null) {
      System.err.println("WARNING: Server was not running at destroy");
      return;
    }
    SzApiProvider.Factory.uninstallProvider(this.providerToken);
    this.server.shutdown(this.accessToken);
    this.server.join();
    this.server = null;
  }

  /**
   * Returns the port that the server should bind to.  By default this returns
   * <tt>null</tt> to indicate that any available port can be used for the
   * server.  Override to use a specific port.
   *
   * @return The port that should be used in initializing the server, or
   * <tt>null</tt> if any available port is fine.
   */
  protected Integer getServerPort() {
    return null;
  }

  /**
   * Retuns the {@link InetAddress} used to initialize the server.  By default
   * this returns the address obtained for <tt>"127.0.0.1"</tt>.  Override this
   * to change the address.  Return <tt>null</tt> if all available interfaces
   * should be bound to.
   *
   * @return The {@link InetAddress} for initializing the server, or
   * <tt>null</tt> if the server should bind to all available network
   * interfaces.
   */
  protected InetAddress getServerAddress() {
    try {
      return InetAddress.getByName("127.0.0.1");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the concurrency with which to initialize the server.  By default
   * this returns one (1).  Override to use a different concurrency.
   *
   * @return The concurrency with which to initialize the server.
   */
  protected int getServerConcurrency() {
    return 1;
  }

  /**
   * Returns the module name with which to initialize the server.  By default
   * this returns <tt>"Test API Server"</tt>.  Override to use a different
   * module name.
   *
   * @return The module name with which to initialize the server.
   */
  protected String getModuleName() {
    return "Test API Server";
  }

  /**
   * Checks whether or not the server should be initialized in verbose mode.
   * By default this <tt>true</tt>.  Override to set to <tt>false</tt>.
   *
   * @return <tt>true</tt> if the server should be initialized in verbose mode,
   * otherwise <tt>false</tt>.
   */
  protected boolean isVerbose() {
    return false;
  }

  /**
   * Internal method for initializing the server.
   */
  private void initializeServer() {
    if (this.server != null) {
      this.destroyServer();
    }
    RepositoryManager.conclude();

    try {
      File iniFile = new File(this.getRepositoryDirectory(), "g2.ini");

      System.err.println("Initializing with INI file: " + iniFile);

      this.server = new SzApiServer(this.accessToken,
                                    0,
                                    getServerAddress(),
                                    this.getServerConcurrency(),
                                    this.getModuleName(),
                                    iniFile,
                                    this.isVerbose());

      this.providerToken = SzApiProvider.Factory.installProvider(server);

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   *
   */
  protected UriInfo newProxyUriInfo(String selfLink) {
    try {
      final URI uri = new URI(selfLink);

      InvocationHandler handler = (p, m, a) -> {
        if (m.getName().equals("getRequestUri")) {
          return uri;
        }
        throw new UnsupportedOperationException(
            "Operation not implemented on proxy UriInfo");
      };

      ClassLoader loader = this.getClass().getClassLoader();
      Class[] classes = {UriInfo.class};

      return (UriInfo) Proxy.newProxyInstance(loader, classes, handler);

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Validates the basic response fields for the specified {@link
   * SzBasicResponse} using the specified self link, timestamp from before
   * calling the service function and timestamp from after calling the
   * service function.
   *
   * @param response        The {@link SzBasicResponse} to validate.
   * @param selfLink        The self link to be expected.
   * @param beforeTimestamp The timestamp from just before calling the service.
   * @param afterTimestamp  The timestamp from just after calling the service.
   */
  protected void validateBasics(SzBasicResponse response,
                                String selfLink,
                                long beforeTimestamp,
                                long afterTimestamp) {
    this.validateBasics(
        null, response, GET, selfLink, beforeTimestamp, afterTimestamp);
  }

  /**
   * Validates the basic response fields for the specified {@link
   * SzBasicResponse} using the specified self link, timestamp from before
   * calling the service function and timestamp from after calling the
   * service function.
   *
   * @param testInfo        Additional test information to be logged with failures.
   * @param response        The {@link SzBasicResponse} to validate.
   * @param selfLink        The self link to be expected.
   * @param beforeTimestamp The timestamp from just before calling the service.
   * @param afterTimestamp  The timestamp from just after calling the service.
   */
  protected void validateBasics(String testInfo,
                                SzBasicResponse response,
                                String selfLink,
                                long beforeTimestamp,
                                long afterTimestamp) {
    this.validateBasics(
        testInfo, response, GET, selfLink, beforeTimestamp, afterTimestamp);
  }

  /**
   * Validates the basic response fields for the specified {@link
   * SzBasicResponse} using the specified self link, timestamp from before
   * calling the service function and timestamp from after calling the
   * service function.
   *
   * @param response           The {@link SzBasicResponse} to validate.
   * @param expectedHttpMethod The {@link SzHttpMethod} that was used.
   * @param selfLink           The self link to be expected.
   * @param beforeTimestamp    The timestamp from just before calling the service.
   * @param afterTimestamp     The timestamp from just after calling the service.
   */
  protected void validateBasics(SzBasicResponse response,
                                SzHttpMethod expectedHttpMethod,
                                String selfLink,
                                long beforeTimestamp,
                                long afterTimestamp)
  {
    this.validateBasics(null,
                        response,
                        expectedHttpMethod,
                        selfLink,
                        beforeTimestamp,
                        afterTimestamp);
  }

  /**
   * Validates the basic response fields for the specified {@link
   * SzBasicResponse} using the specified self link, timestamp from before
   * calling the service function and timestamp from after calling the
   * service function.
   *
   * @param testInfo           Additional test information to be logged with failures.
   * @param response           The {@link SzBasicResponse} to validate.
   * @param expectedHttpMethod The {@link SzHttpMethod} that was used.
   * @param selfLink           The self link to be expected.
   * @param beforeTimestamp    The timestamp from just before calling the service.
   * @param afterTimestamp     The timestamp from just after calling the service.
   */
  protected void validateBasics(String          testInfo,
                                SzBasicResponse response,
                                SzHttpMethod    expectedHttpMethod,
                                String          selfLink,
                                long            beforeTimestamp,
                                long            afterTimestamp)
  {
    String suffix = (testInfo != null && testInfo.trim().length() > 0)
                  ? " ( " + testInfo + " )" : "";

    SzLinks links = response.getLinks();
    SzMeta meta = response.getMeta();
    assertEquals(selfLink, links.getSelf(), "Unexpected self link" + suffix);
    assertEquals(expectedHttpMethod, meta.getHttpMethod(),
                 "Unexpected HTTP method" + suffix);
    assertEquals(200, meta.getHttpStatusCode(), "Unexpected HTTP status code" + suffix);
    assertEquals(MAVEN_VERSION, meta.getVersion(), "Unexpected server version" + suffix);
    assertNotNull(meta.getTimestamp(), "Timestamp unexpectedly null" + suffix);
    long now = meta.getTimestamp().getTime();

    // check the timestamp
    if (now < beforeTimestamp || now > afterTimestamp) {
      fail("Timestamp should be between " + new Date(beforeTimestamp) + " and "
               + new Date(afterTimestamp) + suffix);
    }
    Map<String, Long> timings = meta.getTimings();

    // determine max duration
    long maxDuration = (afterTimestamp - beforeTimestamp);

    timings.entrySet().forEach(entry -> {
      long duration = entry.getValue();
      if (duration > maxDuration) {
        fail("Timing value too large: " + entry.getKey() + " = "
                 + duration + "ms VS " + maxDuration + "ms" + suffix);
      }
    });
  }

  /**
   * Validates the basic response fields for the specified {@link
   * SzResponseWithRawData} using the specified self link, timestamp from before
   * calling the service function, timestamp from after calling the
   * service function, and flag indicating if raw data should be expected.
   *
   * @param response        The {@link SzBasicResponse} to validate.
   * @param selfLink        The self link to be expected.
   * @param beforeTimestamp The timestamp from just before calling the service.
   * @param afterTimestamp  The timestamp from just after calling the service.
   * @param expectRawData   <tt>true</tt> if raw data should be expected,
   *                        otherwise <tt>false</tt>
   */
  protected void validateBasics(SzResponseWithRawData response,
                                String                selfLink,
                                long                  beforeTimestamp,
                                long                  afterTimestamp,
                                boolean               expectRawData)
  {
    this.validateBasics(null,
                        response,
                        selfLink,
                        beforeTimestamp,
                        afterTimestamp,
                        expectRawData);
  }

  /**
   * Validates the basic response fields for the specified {@link
   * SzResponseWithRawData} using the specified self link, timestamp from before
   * calling the service function, timestamp from after calling the
   * service function, and flag indicating if raw data should be expected.
   *
   * @param testInfo        Additional test information to be logged with failures.
   * @param response        The {@link SzBasicResponse} to validate.
   * @param selfLink        The self link to be expected.
   * @param beforeTimestamp The timestamp from just before calling the service.
   * @param afterTimestamp  The timestamp from just after calling the service.
   * @param expectRawData   <tt>true</tt> if raw data should be expected,
   *                        otherwise <tt>false</tt>
   */
  protected void validateBasics(String                testInfo,
                                SzResponseWithRawData response,
                                String                selfLink,
                                long                  beforeTimestamp,
                                long                  afterTimestamp,
                                boolean               expectRawData)
  {
    String suffix = (testInfo != null && testInfo.trim().length() > 0)
        ? " ( " + testInfo + " )" : "";

    this.validateBasics(testInfo, response, selfLink, beforeTimestamp, afterTimestamp);

    Object rawData = response.getRawData();
    if (expectRawData) {
      assertNotNull(rawData, "Raw data unexpectedly non-null" + suffix);
    } else {
      assertNull(rawData, "Raw data unexpectedly null" + suffix);
    }
  }

  /**
   * Invoke an operation on the currently running API server over HTTP.
   *
   * @param httpMethod    The HTTP method to use.
   * @param uri           The relative or absolute URI (optionally including query params)
   * @param responseClass The class of the response.
   * @param <T>           The response type which must extend {@link SzBasicResponse}
   * @return
   */
  protected <T extends SzBasicResponse> T invokeServerViaHttp(
      SzHttpMethod httpMethod,
      String uri,
      Class<T> responseClass) {
    return this.invokeServerViaHttp(
        httpMethod, uri, null, null, responseClass);
  }

  /**
   * Invoke an operation on the currently running API server over HTTP.
   *
   * @param httpMethod    The HTTP method to use.
   * @param uri           The relative or absolute URI (optionally including query params)
   * @param queryParams   The optional map of query parameters.
   * @param bodyContent   The object to be converted to JSON for body content.
   * @param responseClass The class of the response.
   * @param <T>           The response type which must extend {@link SzBasicResponse}
   * @return
   */
  protected <T extends SzBasicResponse> T invokeServerViaHttp(
      SzHttpMethod httpMethod,
      String uri,
      Map<String, ?> queryParams,
      Object bodyContent,
      Class<T> responseClass) {
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      if (!uri.toLowerCase().startsWith("http://")) {
        uri = this.formatServerUri(uri);
      }
      if (queryParams != null && queryParams.size() > 0) {
        String initialPrefix = uri.contains("?") ? "&" : "?";

        StringBuilder sb = new StringBuilder();
        queryParams.entrySet().forEach(entry -> {
          String key = entry.getKey();
          Object value = entry.getValue();
          Collection values = null;
          if (value instanceof Collection) {
            values = (Collection) value;
          } else {
            values = Collections.singletonList(value);
          }
          try {
            key = URLEncoder.encode(key, "UTF-8");
            for (Object val : values) {
              if (val == null) return;
              String textValue = val.toString();
              textValue = URLEncoder.encode(textValue, "UTF-8");
              sb.append((sb.length() == 0) ? initialPrefix : "&");
              sb.append(key).append("=").append(textValue);
            }
          } catch (UnsupportedEncodingException cannotHappen) {
            throw new RuntimeException(cannotHappen);
          }
        });
        uri = uri + sb.toString();
      }

      String jsonContent = null;
      if (bodyContent != null) {
        jsonContent = objectMapper.writeValueAsString(bodyContent);
      }

      URL url = new URL(uri);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod(httpMethod.toString());
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("Accept-Charset", "utf-8");
      if (jsonContent != null) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStreamWriter osw = new OutputStreamWriter(baos, "UTF-8");
        osw.write(jsonContent);
        osw.flush();
        byte[] bytes = baos.toByteArray();
        int length = bytes.length;
        conn.addRequestProperty("Content-Length", "" + length);
        conn.addRequestProperty("Content-Type",
                                "application/json; charset=utf-8");
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write(bytes);
        os.flush();
      }

      InputStream is = conn.getInputStream();
      InputStreamReader isr = new InputStreamReader(is, "UTF-8");
      BufferedReader br = new BufferedReader(isr);
      StringBuilder sb = new StringBuilder();
      for (int nextChar = br.read(); nextChar >= 0; nextChar = br.read()) {
        sb.append((char) nextChar);
      }

      String responseJson = sb.toString();

      return objectMapper.readValue(responseJson, responseClass);

    } catch (RuntimeException e) {
      e.printStackTrace();
      throw e;
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  /**
   * Validates the raw data and ensures the expected JSON property keys are
   * present and that no unexpected keys are present.
   *
   * @param rawData      The raw data to validate.
   * @param expectedKeys The zero or more expected property keys.
   */
  protected void validateRawDataMap(Object rawData, String... expectedKeys) {
    this.validateRawDataMap(null,
                            rawData,
                            true,
                            expectedKeys);
  }

  /**
   * Validates the raw data and ensures the expected JSON property keys are
   * present and that no unexpected keys are present.
   *
   * @param testInfo     Additional test information to be logged with failures.
   * @param rawData      The raw data to validate.
   * @param expectedKeys The zero or more expected property keys.
   */
  protected void validateRawDataMap(String    testInfo,
                                    Object    rawData,
                                    String... expectedKeys)
  {
    this.validateRawDataMap(testInfo, rawData, true, expectedKeys);
  }

  /**
   * Validates the raw data and ensures the expected JSON property keys are
   * present and that, optionally, no unexpected keys are present.
   *
   * @param rawData      The raw data to validate.
   * @param strict       Whether or not property keys other than those specified are
   *                     allowed to be present.
   * @param expectedKeys The zero or more expected property keys -- these are
   *                     either a minimum or exact set depending on the
   *                     <tt>strict</tt> parameter.
   */
  protected void validateRawDataMap(Object    rawData,
                                    boolean   strict,
                                    String... expectedKeys)
  {
    this.validateRawDataMap(null, rawData, strict, expectedKeys);
  }

  /**
   * Validates the raw data and ensures the expected JSON property keys are
   * present and that, optionally, no unexpected keys are present.
   *
   *
   * @param testInfo     Additional test information to be logged with failures.
   * @param rawData      The raw data to validate.
   * @param strict       Whether or not property keys other than those specified are
   *                     allowed to be present.
   * @param expectedKeys The zero or more expected property keys -- these are
   *                     either a minimum or exact set depending on the
   *                     <tt>strict</tt> parameter.
   */
  protected void validateRawDataMap(String    testInfo,
                                    Object    rawData,
                                    boolean   strict,
                                    String... expectedKeys)
  {
    String suffix = (testInfo != null && testInfo.trim().length() > 0)
        ? " ( " + testInfo + " )" : "";

    if (rawData == null) {
      fail("Expected raw data but got null value" + suffix);
    }

    if (!(rawData instanceof Map)) {
      fail("Raw data is not a JSON object: " + rawData + suffix);
    }

    Map<String, Object> map = (Map<String, Object>) rawData;
    Set<String> expectedKeySet = new HashSet<>();
    Set<String> actualKeySet = map.keySet();
    for (String key : expectedKeys) {
      expectedKeySet.add(key);
      if (!actualKeySet.contains(key)) {
        fail("JSON property missing from raw data: " + key + " / " + map
             + suffix);
      }
    }
    if (strict && expectedKeySet.size() != actualKeySet.size()) {
      Set<String> extraKeySet = new HashSet<>(actualKeySet);
      extraKeySet.removeAll(expectedKeySet);
      fail("Unexpected JSON properties in raw data: " + extraKeySet + suffix);
    }

  }


  /**
   * Validates the raw data and ensures it is a collection of objects and the
   * expected JSON property keys are present in the array objects and that no
   * unexpected keys are present.
   *
   * @param rawData      The raw data to validate.
   * @param expectedKeys The zero or more expected property keys.
   */
  protected void validateRawDataMapArray(Object rawData, String... expectedKeys)
  {
    this.validateRawDataMapArray(null, rawData, true, expectedKeys);
  }

  /**
   * Validates the raw data and ensures it is a collection of objects and the
   * expected JSON property keys are present in the array objects and that no
   * unexpected keys are present.
   *
   * @param testInfo     Additional test information to be logged with failures.
   * @param rawData      The raw data to validate.
   * @param expectedKeys The zero or more expected property keys.
   */
  protected void validateRawDataMapArray(String     testInfo,
                                         Object     rawData,
                                         String...  expectedKeys)
  {
    this.validateRawDataMapArray(testInfo, rawData, true, expectedKeys);
  }

  /**
   * Validates the raw data and ensures it is a collection of objects and the
   * expected JSON property keys are present in the array objects and that,
   * optionally, no unexpected keys are present.
   *
   * @param rawData      The raw data to validate.
   * @param strict       Whether or not property keys other than those specified are
   *                     allowed to be present.
   * @param expectedKeys The zero or more expected property keys for the array
   *                     objects -- these are either a minimum or exact set
   *                     depending on the <tt>strict</tt> parameter.
   */
  protected void validateRawDataMapArray(Object     rawData,
                                         boolean    strict,
                                         String...  expectedKeys)
  {
    this.validateRawDataMapArray(null, rawData, strict, expectedKeys);
  }

  /**
   * Validates the raw data and ensures it is a collection of objects and the
   * expected JSON property keys are present in the array objects and that,
   * optionally, no unexpected keys are present.
   *
   * @param testInfo     Additional test information to be logged with failures.
   * @param rawData      The raw data to validate.
   * @param strict       Whether or not property keys other than those specified are
   *                     allowed to be present.
   * @param expectedKeys The zero or more expected property keys for the array
   *                     objects -- these are either a minimum or exact set
   *                     depending on the <tt>strict</tt> parameter.
   */
  protected void validateRawDataMapArray(String     testInfo,
                                         Object     rawData,
                                         boolean    strict,
                                         String...  expectedKeys)
  {
    String suffix = (testInfo != null && testInfo.trim().length() > 0)
        ? " ( " + testInfo + " )" : "";

    if (rawData == null) {
      fail("Expected raw data but got null value" + suffix);
    }

    if (!(rawData instanceof Collection)) {
      fail("Raw data is not a JSON array: " + rawData + suffix);
    }

    Collection<Object> collection = (Collection<Object>) rawData;
    Set<String> expectedKeySet = new HashSet<>();
    for (String key : expectedKeys) {
      expectedKeySet.add(key);
    }

    for (Object obj : collection) {
      if (!(obj instanceof Map)) {
        fail("Raw data is not a JSON array of JSON objects: " + rawData + suffix);
      }

      Map<String, Object> map = (Map<String, Object>) obj;

      Set<String> actualKeySet = map.keySet();
      for (String key : expectedKeySet) {
        if (!actualKeySet.contains(key)) {
          fail("JSON property missing from raw data array element: "
                   + key + " / " + map + suffix);
        }
      }
      if (strict && expectedKeySet.size() != actualKeySet.size()) {
        Set<String> extraKeySet = new HashSet<>(actualKeySet);
        extraKeySet.removeAll(expectedKeySet);
        fail("Unexpected JSON properties in raw data: " + extraKeySet + suffix);
      }
    }
  }

  /**
   * Quotes the specified text as a quoted string for a CSV value or header.
   *
   * @param text The text to be quoted.
   * @return The quoted text.
   */
  protected String csvQuote(String text) {
    if (text.indexOf("\"") < 0 && text.indexOf("\\") < 0) {
      return "\"" + text + "\"";
    }
    char[] textChars = text.toCharArray();
    StringBuilder sb = new StringBuilder(text.length() * 2);
    for (char c : textChars) {
      if (c == '"' || c == '\\') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * Creates a CSV temp file with the specified headers and records.
   *
   * @param filePrefix The prefix for the temp file name.
   * @param headers    The CSV headers.
   * @param records    The one or more records.
   * @return The {@link File} that was created.
   */
  protected File prepareCSVFile(String filePrefix,
                                String[] headers,
                                String[]... records) {
    // check the arguments
    int count = headers.length;
    for (int index = 0; index < records.length; index++) {
      String[] record = records[index];
      if (record.length != count) {
        throw new IllegalArgumentException(
            "The header and records do not all have the same number of "
                + "elements.  expected=[ " + count + " ], received=[ "
                + record.length + " ], index=[ " + index + " ]");
      }
    }

    try {
      File csvFile = File.createTempFile(filePrefix, ".csv");

      // populate the file as a CSV
      try (FileOutputStream fos = new FileOutputStream(csvFile);
           OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
           PrintWriter pw = new PrintWriter(osw)) {
        String prefix = "";
        for (String header : headers) {
          pw.print(prefix);
          pw.print(csvQuote(header));
          prefix = ",";
        }
        pw.println();
        pw.flush();

        for (String[] record : records) {
          prefix = "";
          for (String value : record) {
            pw.print(prefix);
            pw.print(csvQuote(value));
            prefix = ",";
          }
          pw.println();
          pw.flush();
        }
        pw.flush();

      }

      return csvFile;

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


  /**
   * Creates a JSON array temp file with the specified headers and records.
   *
   * @param filePrefix The prefix for the temp file name.
   * @param headers    The CSV headers.
   * @param records    The one or more records.
   * @return The {@link File} that was created.
   */
  protected File prepareJsonArrayFile(String filePrefix,
                                      String[] headers,
                                      String[]... records) {
    // check the arguments
    int count = headers.length;
    for (int index = 0; index < records.length; index++) {
      String[] record = records[index];
      if (record.length != count) {
        throw new IllegalArgumentException(
            "The header and records do not all have the same number of "
                + "elements.  expected=[ " + count + " ], received=[ "
                + record.length + " ], index=[ " + index + " ]");
      }
    }

    try {
      File jsonFile = File.createTempFile(filePrefix, ".json");

      // populate the file with a JSON array
      try (FileOutputStream fos = new FileOutputStream(jsonFile);
           OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8")) {
        JsonArrayBuilder jab = Json.createArrayBuilder();
        JsonObjectBuilder job = Json.createObjectBuilder();
        for (String[] record : records) {
          for (int index = 0; index < record.length; index++) {
            String key = headers[index];
            String value = record[index];
            job.add(key, value);
          }
          jab.add(job);
        }

        String jsonText = JsonUtils.toJsonText(jab);
        osw.write(jsonText);
        osw.flush();
      }

      return jsonFile;

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a JSON temp file with the specified headers and records.
   *
   * @param filePrefix The prefix for the temp file name.
   * @param headers    The CSV headers.
   * @param records    The one or more records.
   * @return The {@link File} that was created.
   */
  protected File prepareJsonFile(String filePrefix,
                                 String[] headers,
                                 String[]... records) {
    // check the arguments
    int count = headers.length;
    for (int index = 0; index < records.length; index++) {
      String[] record = records[index];
      if (record.length != count) {
        throw new IllegalArgumentException(
            "The header and records do not all have the same number of "
                + "elements.  expected=[ " + count + " ], received=[ "
                + record.length + " ], index=[ " + index + " ]");
      }
    }

    try {
      File jsonFile = File.createTempFile(filePrefix, ".json");

      // populate the file as one JSON record per line
      try (FileOutputStream fos = new FileOutputStream(jsonFile);
           OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
           PrintWriter pw = new PrintWriter(osw)) {
        for (String[] record : records) {
          JsonObjectBuilder job = Json.createObjectBuilder();
          for (int index = 0; index < record.length; index++) {
            String key = headers[index];
            String value = record[index];
            job.add(key, value);
          }
          String jsonText = JsonUtils.toJsonText(job);
          pw.println(jsonText);
          pw.flush();
        }
        pw.flush();
      }

      return jsonFile;

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Compares two collections to ensure they have the same elements.
   *
   */
  protected void assertSameElements(Collection expected,
                                    Collection actual,
                                    String     description)
  {
    if (expected != null) {
      expected = upperCase(expected);
      actual   = upperCase(actual);
      assertNotNull(actual, "Unexpected null " + description);
      if (!actual.containsAll(expected)) {
        Set missing = new HashSet(expected);
        missing.removeAll(actual);
        fail("Missing one or more expected " + description + ".  missing=[ "
             + missing + " ], actual=[ " + actual + " ]");
      }
      if (!expected.containsAll(actual)) {
        Set extras = new HashSet(actual);
        extras.removeAll(expected);
        fail("One or more extra " + description + ".  extras=[ "
             + extras + " ], actual=[ " + actual + " ]");
      }
    }
  }

  /**
   * Converts the {@link String} elements in the specified {@link Collection}
   * to upper case and returns a {@link Set} contianing all values.
   *
   * @param c The {@link Collection} to process.
   *
   * @return The {@link Set} containing the same elements with the {@link
   *         String} elements converted to upper case.
   */
  protected static Set upperCase(Collection c) {
    Set set = new LinkedHashSet();
    for (Object obj : c) {
      if (obj instanceof String) {
        obj = ((String) obj).toUpperCase();
      }
      set.add(obj);
    }
    return set;
  }

  /**
   * Utility method for creating a {@link Set} to use in validation.
   *
   * @param elements The zero or more elements in the set.
   *
   * @return The {@link Set} of elements.
   */
  protected static <T> Set<T> set(T... elements) {
    Set<T> set = new LinkedHashSet<>();
    for (T element : elements) {
      set.add(element);
    }
    return set;
  }

  /**
   * Utility method for creating a {@link List} to use in validation.
   *
   * @param elements The zero or more elements in the list.
   *
   * @return The {@link Set} of elements.
   */
  protected static <T> List<T> list(T... elements) {
    List<T> list = new ArrayList<>(elements.length);
    for (T element : elements) {
      list.add(element);
    }
    return list;
  }

}
