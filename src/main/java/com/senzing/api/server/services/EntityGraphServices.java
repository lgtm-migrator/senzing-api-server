package com.senzing.api.server.services;

import com.senzing.api.model.*;
import com.senzing.api.server.SzApiServer;
import com.senzing.g2.engine.G2Engine;
import com.senzing.util.JsonUtils;

import javax.json.*;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.senzing.api.model.SzHttpMethod.GET;
import static com.senzing.api.server.services.ServicesUtil.*;
import static com.senzing.g2.engine.G2Engine.*;

/**
 * Provides entity graph related API services.
 */
@Path("/")
@Produces("application/json; charset=UTF-8")
public class EntityGraphServices {
  @GET
  @Path("entity-paths")
  public SzEntityPathResponse getEntityPath(
      @QueryParam("from") String fromParam,
      @QueryParam("to") String toParam,
      @DefaultValue("3") @QueryParam("maxDegrees") int maxDegrees,
      @QueryParam("x") List<String> avoidParam,
      @DefaultValue("false") @QueryParam("forbidAvoided") boolean forbidAvoided,
      @QueryParam("s") List<String> sourcesParam,
      @DefaultValue("false") @QueryParam("withRaw") boolean withRaw,
      @Context UriInfo uriInfo)
  {
    SzApiServer server = SzApiServer.getInstance();

    return server.executeInThread(() -> {

      SzEntityIdentifier        from;
      SzEntityIdentifier        to;
      List<SzEntityIdentifier>  avoidEntities = null;
      List<String>              withSources   = null;
      try {
        if (fromParam == null) {
          throw newBadRequestException(
              GET, uriInfo,
              "Parameter missing or empty: \"from\".  "
                  + "The 'from' entity identifier is required.");
        }
        if (toParam == null) {
          throw newBadRequestException(
              GET, uriInfo,
              "Parameter missing or empty: \"to\".  "
                  + "The 'to' entity identifier is required.");
        }

        try {
          from = SzEntityIdentifier.valueOf(fromParam.trim());
        } catch (Exception e) {
          throw newBadRequestException(
              GET, uriInfo,
              "Parameter is not formatted correctly: \"from\".");
        }

        try {
          to = SzEntityIdentifier.valueOf(toParam.trim());
        } catch (Exception e) {
          throw newBadRequestException(
              GET, uriInfo,
              "Parameter is not formatted correctly: \"to\".");
        }

        // check for consistent from/to
        if (from.getClass() != to.getClass()) {
          throw newBadRequestException(
              GET, uriInfo,
              "Entity identifiers must be consistent types.  from=" + from
                  + ", to=" + to);
        }

        if (avoidParam != null && avoidParam.size() > 0) {
          avoidEntities = parseEntityIdentifiers(
              avoidParam, "avoidEntities", GET, uriInfo);

          if (!checkConsistent(avoidEntities)) {
            throw newBadRequestException(
                GET, uriInfo,
                "Entity identifiers for avoided entities must be of "
                    + "consistent types: " + avoidEntities);
          }
        }

        if (sourcesParam != null && sourcesParam.size() > 0) {
          Set<String> dataSources = server.getDataSources();
          withSources = new ArrayList<>(dataSources.size());

          for (String source : sourcesParam) {
            if (dataSources.contains(source)) {
              withSources.add(source);
            } else {
              throw newBadRequestException(
                  GET, uriInfo,
                  "Unrecognized data source: " + source);
            }
          }
        }
        if (maxDegrees < 1) {
          throw newBadRequestException(
              GET, uriInfo,
              "Max degrees must be greater than zero: " + maxDegrees);
        }
      } catch (WebApplicationException e) {
        throw e;
      } catch (Exception e) {
        throw newBadRequestException(GET, uriInfo, e.getMessage());
      }

      try {
        // get the engine API and the config API
        G2Engine engineApi = server.getEngineApi();

        StringBuffer responseDataBuffer = new StringBuffer();

        int result;
        if (from.getClass() == SzRecordId.class) {
          String source1 = ((SzRecordId) from).getDataSourceCode();
          String source2 = ((SzRecordId) to).getDataSourceCode();
          String id1 = ((SzRecordId) from).getRecordId();
          String id2 = ((SzRecordId) to).getRecordId();

          if (avoidEntities == null && withSources == null) {
            result = engineApi.findPathByRecordID(source1,
                                                  id1,
                                                  source2,
                                                  id2,
                                                  maxDegrees,
                                                  responseDataBuffer);
          } else if (withSources == null) {
            result = engineApi.findPathExcludingByRecordID(
                source1,
                id1,
                source2,
                id2,
                maxDegrees,
                nativeJsonEncodeEntityIds(avoidEntities),
                (forbidAvoided ? 0 : G2_FIND_PATH_PREFER_EXCLUDE),
                responseDataBuffer);

          } else {
            result = engineApi.findPathIncludingSourceByRecordID(
                source1,
                id1,
                source2,
                id2,
                maxDegrees,
                nativeJsonEncodeEntityIds(avoidEntities),
                nativeJsonEncodeDataSources(withSources),
                (forbidAvoided ? 0 : G2_FIND_PATH_PREFER_EXCLUDE),
                responseDataBuffer);
          }
        } else {
          SzEntityId id1 = (SzEntityId) from;
          SzEntityId id2 = (SzEntityId) to;

          if (avoidEntities == null && withSources == null) {
            result = engineApi.findPathByEntityID(id1.getValue(),
                                                  id2.getValue(),
                                                  maxDegrees,
                                                  responseDataBuffer);
          } else if (withSources == null) {
            result = engineApi.findPathExcludingByEntityID(
                id1.getValue(),
                id2.getValue(),
                maxDegrees,
                nativeJsonEncodeEntityIds(avoidEntities),
                (forbidAvoided ? 0 : G2_FIND_PATH_PREFER_EXCLUDE),
                responseDataBuffer);

          } else {
            result = engineApi.findPathIncludingSourceByEntityID(
                id1.getValue(),
                id2.getValue(),
                maxDegrees,
                nativeJsonEncodeEntityIds(avoidEntities),
                nativeJsonEncodeDataSources(withSources),
                (forbidAvoided ? 0 : G2_FIND_PATH_PREFER_EXCLUDE),
                responseDataBuffer);
          }
        }

        if (result != 0) {
          throw newInternalServerErrorException(GET, uriInfo, engineApi);
        }

        // parse the raw data
        String rawData = responseDataBuffer.toString();
        JsonObject jsonObject = JsonUtils.parseJsonObject(rawData);
        SzEntityPathData entityPathData
            = SzEntityPathData.parseEntityPathData(
                jsonObject,
                server::getAttributeClassForFeature);

        // construct the response
        SzEntityPathResponse response
            = new SzEntityPathResponse(GET,
                                       200,
                                       uriInfo,
                                       entityPathData);

        // if including raw data then add it
        if (withRaw) response.setRawData(rawData);

        // return the response
        return response;

      } catch (WebApplicationException e) {
        throw e;

      } catch (Exception e) {
        throw ServicesUtil.newInternalServerErrorException(GET, uriInfo, e);
      }
    });
  }

  @GET
  @Path("entity-networks")
  public SzEntityNetworkResponse getEntityNetwork(
      @QueryParam("e") List<String> entitiesParam,
      @DefaultValue("5")      @QueryParam("maxDegrees")  int     maxDegrees,
      @DefaultValue("1")      @QueryParam("buildOut")    int     buildOut,
      @DefaultValue("1000")   @QueryParam("maxEntities") int     maxEntities,
      @DefaultValue("false")  @QueryParam("withRaw")     boolean withRaw,
      @Context                                           UriInfo uriInfo)
  {
    SzApiServer server = SzApiServer.getInstance();

    return server.executeInThread(() -> {
      List<SzEntityIdentifier> entities;
      // check for consistent entity IDs
      try {
        if (entitiesParam == null || entitiesParam.isEmpty()) {
          throw newBadRequestException(
              GET, uriInfo,
              "Parameter missing or empty: \"entities\".  "
                  + "One or more 'entities' entity identifiers are required.");
        }

        entities = parseEntityIdentifiers(
            entitiesParam, "e", GET, uriInfo);

        if (!checkConsistent(entities)) {
          throw newBadRequestException(
              GET, uriInfo,
              "Entity identifiers for entities must be of consistent "
              + "types: " + entities);
        }

        if (maxDegrees < 1) {
          throw newBadRequestException(
              GET, uriInfo,
              "Max degrees must be greater than zero: " + maxDegrees);
        }

        if (buildOut < 0) {
          throw newBadRequestException(
              GET, uriInfo,
              "Build out must be zero or greater: " + buildOut);
        }

        if (maxEntities < 1) {
          throw newBadRequestException(
              GET, uriInfo,
              "Max entities must be greater than zero: " + maxEntities);
        }

      } catch (WebApplicationException e) {
        throw e;
      } catch (Exception e) {
        throw newBadRequestException(GET, uriInfo, e.getMessage());
      }

      try {
        // get the engine API and the config API
        G2Engine engineApi = server.getEngineApi();

        StringBuffer sb = new StringBuffer();

        int result;

        if (entities.iterator().next().getClass() == SzRecordId.class) {
          result = engineApi.findNetworkByRecordID(
              nativeJsonEncodeEntityIds(entities),
              maxDegrees,
              buildOut,
              maxEntities,
              sb);

        } else {
          result = engineApi.findNetworkByEntityID(
              nativeJsonEncodeEntityIds(entities),
              maxDegrees,
              buildOut,
              maxEntities,
              sb);
        }

        if (result != 0) {
          throw newInternalServerErrorException(GET, uriInfo, engineApi);
        }

        // parse the raw data
        String rawData = sb.toString();
        JsonObject jsonObject = JsonUtils.parseJsonObject(rawData);

        SzEntityNetworkData entityNetworkData
            = SzEntityNetworkData.parseEntityNetworkData(
            jsonObject,
            server::getAttributeClassForFeature);

        // construct the response
        SzEntityNetworkResponse response
            = new SzEntityNetworkResponse(GET,
                                          200,
                                          uriInfo,
                                          entityNetworkData);

        // if including raw data then add it
        if (withRaw) response.setRawData(rawData);

        // return the response
        return response;

      } catch (WebApplicationException e) {
        throw e;

      } catch (Exception e) {
        throw ServicesUtil.newInternalServerErrorException(GET, uriInfo, e);
      }
    });
  }

  /**
   * Checks if the entity ID's in the specified list are of a consistent type.
   *
   * @param entities The list of {@link SzEntityIdentifier} instances.
   */
  private static boolean checkConsistent(List<SzEntityIdentifier> entities) {
    if (entities != null && !entities.isEmpty()) {
      Class idClass = null;
      for (SzEntityIdentifier id : entities) {
        if (idClass == null) {
          idClass = id.getClass();
        } else if (idClass != id.getClass()) {
          return false;
        }
      }
    }
    return true;
  }
}
