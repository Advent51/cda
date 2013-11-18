/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package pt.webdetails.cda;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;

import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.QueryParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MultivaluedMap;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.apache.commons.lang.StringUtils;
import pt.webdetails.cda.dataaccess.AbstractDataAccess;
import pt.webdetails.cda.dataaccess.DataAccessConnectionDescriptor;
import pt.webdetails.cda.exporter.ExportOptions;
import pt.webdetails.cda.exporter.ExportedQueryResult;
import pt.webdetails.cda.exporter.Exporter;
import pt.webdetails.cda.exporter.ExporterException;
import pt.webdetails.cda.exporter.UnsupportedExporterException;
import pt.webdetails.cda.services.CacheManager;
import pt.webdetails.cda.services.Editor;
import pt.webdetails.cda.services.ExtEditor;
import pt.webdetails.cda.services.Previewer;
import pt.webdetails.cda.settings.SettingsManager;

import org.pentaho.platform.api.engine.IPentahoSession;

import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.security.SecurityHelper;

import pt.webdetails.cda.utils.DoQueryParameters;
import pt.webdetails.cpf.PluginEnvironment;
import pt.webdetails.cpf.messaging.JsonGeneratorSerializable;
import pt.webdetails.cpf.messaging.JsonResult;
import pt.webdetails.cpf.utils.CharsetHelper;
import pt.webdetails.cpf.utils.JsonHelper;
import pt.webdetails.cpf.utils.MimeTypes;

@Path("/{plugin}/api")
public class CdaUtils {
  private static final Log logger = LogFactory.getLog(CdaUtils.class);

  public static final String PLUGIN_NAME = "cda";
  private static final String PREFIX_PARAMETER = "param";
  private static final String PREFIX_SETTING = "setting";

//  private static final String[] EXPORT_TYPES = {MimeTypes.JSON, MimeTypes.XML, MimeTypes.CSV};//TODO
  
  private static final Pattern CDA_PATH = Pattern.compile( "^[^:]*([^/]+)[^?]*" );//TODO: safer to get trom repos?
  
  public CdaUtils() {
  }

  protected static String getEncoding() { return CharsetHelper.getEncoding(); }

  //TODO: wildcard for exported types?
  @GET
  @Path( "/doQuery" )
  @Produces( {MimeTypes.JSON, MimeTypes.XML, MimeTypes.CSV, MimeTypes.XLS, MimeTypes.PLAIN_TEXT, MimeTypes.HTML} )
  public StreamingOutput doQueryGet( @Context UriInfo urii ) throws WebApplicationException {
    return doQuery( urii.getQueryParameters() );
  }

  @POST
  @Path( "/doQuery" )
  @Consumes ( APPLICATION_FORM_URLENCODED )
  @Produces( {MimeTypes.JSON, MimeTypes.XML, MimeTypes.CSV, MimeTypes.XLS, MimeTypes.PLAIN_TEXT, MimeTypes.HTML} )
  public StreamingOutput doQueryPost( MultivaluedMap<String, String> formParams ) throws WebApplicationException {
    return doQuery( formParams );
  }

  public StreamingOutput doQuery( MultivaluedMap<String, String> params ) throws WebApplicationException {
    try {
      DoQueryParameters parameters = getDoQueryParameters( params );
      if( parameters.isWrapItUp() ) {
        return wrapQuery( parameters );
      }
      CdaCoreService core = getCdaCoreService();
      return toStreamingOutput( core.doQuery( parameters ) );
    } catch ( Exception e ) {
      throw new WebApplicationException( e, 501 );// TODO:
    }
  }

  private StreamingOutput wrapQuery( DoQueryParameters parameters ) throws Exception {
    final String uuid = getCdaCoreService().wrapQuery( parameters );
    return new StreamingOutput() {
      public void write( OutputStream out ) throws IOException, WebApplicationException {
        IOUtils.write( uuid, out );
      }
    };
  }

  private DoQueryParameters getDoQueryParameters( MultivaluedMap<String, String> parameters ) throws Exception {
    DoQueryParameters doQueryParams = new DoQueryParameters( );

    //should populate everything but prefixed parameters TODO: recheck defaults
    BeanUtils.populate( doQueryParams, parameters );

    Map<String, Object> params = new HashMap<String, Object>();
    Map<String, Object> extraSettings = new HashMap<String, Object>();
    for ( String name : parameters.keySet() ) {
      if ( name.startsWith( PREFIX_PARAMETER ) ) {
        params.put( name.substring( PREFIX_PARAMETER.length() ), getParam( parameters.get( name ) ) );
      } else if ( name.startsWith( PREFIX_SETTING ) ) {
        extraSettings.put( name.substring( PREFIX_SETTING.length() ), getParam( parameters.get( name ) ) );
      }
    }
    doQueryParams.setParameters( params );
    doQueryParams.setExtraSettings( extraSettings );

    return doQueryParams;
  }

  private Object getParam( List<String> paramValues ) {
    if( paramValues == null ) return null;
    if (paramValues.size() == 1) return paramValues.get( 0 );
    return paramValues;
  }

  @GET
  @Path( "/unwrapQuery" )
  @Produces( { MimeTypes.JSON, MimeTypes.XML, MimeTypes.CSV, MimeTypes.XLS, MimeTypes.PLAIN_TEXT, MimeTypes.HTML } )
  public StreamingOutput unwrapQuery( @QueryParam( "path" ) String path, @QueryParam( "uuid" ) String uuid,
      @Context HttpServletResponse servletResponse, @Context HttpServletRequest servletRequest )
    throws WebApplicationException {
    try {
      return toStreamingOutput( getCdaCoreService().unwrapQuery( path, uuid ) );
    } catch ( Exception e ) {
      logger.error( e );
      throw new WebApplicationException( e, Response.Status.INTERNAL_SERVER_ERROR );
    }
  }

  @GET
  @Path("/listQueries")
  @Produces( {MimeTypes.JSON, MimeTypes.XML, MimeTypes.CSV, MimeTypes.XLS, MimeTypes.PLAIN_TEXT, MimeTypes.HTML} )
  public StreamingOutput listQueries(
      @QueryParam("path") String path, 
      @DefaultValue("json") @QueryParam("outputType") String outputType, 
      @Context HttpServletResponse servletResponse) throws WebApplicationException
  {
    if(StringUtils.isEmpty(path)){
      throw new IllegalArgumentException("No path provided");
    }

    logger.debug("Do Query: getSolPath:" + PentahoSystem.getApplicationContext().getSolutionPath(path));
    ExportedQueryResult result;
    try {
      result = getCdaCoreService().listQueries( path, getSimpleExportOptions( outputType ));
    } catch ( Exception e ) {
      logger.error(e);
      throw new WebApplicationException( e );
    }
    return toStreamingOutput( result );
  }

  private StreamingOutput toStreamingOutput (final ExportedQueryResult result ) {
    return new StreamingOutput() {
      
      public void write( OutputStream out ) throws IOException, WebApplicationException {
        try {
          result.writeOut( out );
        } catch ( ExporterException e ) {
          throw new WebApplicationException( e );
        }
      }
    };
  }

  private StreamingOutput toStreamingOutput( final JsonGeneratorSerializable json ) {
    return new StreamingOutput() {
      public void write( OutputStream out ) throws IOException, WebApplicationException {
         JsonHelper.writeJson( json, out );
      }
    };
  }

  private StreamingOutput toErrorResult( final Exception e ) {
    logger.error( e.getLocalizedMessage(), e );
    return new StreamingOutput() {
      public void write( OutputStream out ) throws IOException, WebApplicationException {
         JsonHelper.writeJson( new JsonResult( false, e.getLocalizedMessage() ), out );
      }
    };
  }

  private ExportOptions getSimpleExportOptions( final String outputType ) {
    return new ExportOptions() {
      
      public String getOutputType() {
        return outputType;
      }
      
      public Map<String, String> getExtraSettings() {
        return Collections.<String,String>emptyMap();
      }
    };
  }

  @GET
  @Path( "/listParameters" )
  @Produces( { MimeTypes.JSON, MimeTypes.XML, MimeTypes.CSV, MimeTypes.XLS, MimeTypes.PLAIN_TEXT, MimeTypes.HTML } )
  public StreamingOutput listParameters( @QueryParam( "path" ) String path,
      @QueryParam( "dataAccessId" ) String dataAccessId,
      @DefaultValue( "json" ) @QueryParam( "outputType" ) String outputType ) throws WebApplicationException {
    logger.debug( "Do Query: getSolPath:" + path );
    try {
      return toStreamingOutput( getCdaCoreService().listParameters( path, dataAccessId,
          getSimpleExportOptions( outputType ) ) );
    } catch ( Exception e ) {
      logger.error( e );
      throw new WebApplicationException( e );
    }
  }

  private Exporter useExporter( final CdaEngine engine, final String outputType, HttpServletResponse servletResponse )
    throws UnsupportedExporterException {
    return useExporter( engine, new ExportOptions() {

      public String getOutputType() {
        return outputType;
      }

      public Map<String, String> getExtraSettings() {
        return null;
      }
    }, servletResponse );
  }

  private Exporter useExporter( final CdaEngine engine, ExportOptions opts, HttpServletResponse servletResponse )
      throws UnsupportedExporterException {
      // Handle the query itself and its output format...
      Exporter exporter = engine.getExporter( opts );
      String mimeType = exporter.getMimeType();
      if(mimeType != null)
      {
        servletResponse.setHeader("Content-Type", mimeType);
      }

      String attachmentName = exporter.getAttachmentName();
      if ( attachmentName != null ) {
        servletResponse.setHeader( "content-disposition", "attachment; filename=" + attachmentName );
      }
      return exporter;
  }

  @GET
  @Path("/getCdaFile")
  @Produces( MimeTypes.JSON )
  public StreamingOutput getCdaFile(@QueryParam("path") String path) throws WebApplicationException
  {
    String filePath = StringUtils.replace(path, "///", "/");
    JsonGeneratorSerializable json;
    try {
      String document = getEditor().getFile( filePath );
      if ( document != null ) {
        json = new JsonResult( true, document );
      }
      else {
        json = new JsonResult( false, "Unable to read file." );
      }
    }
    catch (Exception e) {
      return toErrorResult( e );
    }
    return toStreamingOutput( json );
  }

  @GET
  @Path("/canEdit")
  @Produces( MimeTypes.JSON )
  public StreamingOutput canEdit(@QueryParam("path") String path) {
    boolean canEdit = getEditor().canEdit( path );
    return toStreamingOutput(new JsonResult( true, JsonHelper.toJson( canEdit ) ) );
  }

  private Editor getEditor() {
    return new Editor();
  }

  @POST
  @Path("/writeCdaFile")
  @Produces( MimeTypes.JSON )
  public StreamingOutput writeCdaFile(@FormParam("path") String path, 
                                      @FormParam("data") String data ) throws Exception
  {  
    //TODO: Validate the filename in some way, shape or form!
    if ( data == null ) {
      logger.error( "writeCdaFile: no data to save provided " + path );
      return toStreamingOutput( new JsonResult( false, "No Data!" ) );
    }

    try {
      return toStreamingOutput( new JsonResult( getEditor().writeFile( path, data ), path ) );
    }
    catch (Exception e) {
      return toErrorResult( e );
    }
  }

  @GET
  @Path("/getCdaList")
  @Consumes({ APPLICATION_XML, APPLICATION_JSON })
  public void getCdaList(@DefaultValue("json") @QueryParam("outputType") String outputType, 
                            
                         @Context HttpServletResponse servletResponse, 
                         @Context HttpServletRequest servletRequest) throws Exception
  {
    final CdaEngine engine = CdaEngine.getInstance();
    Exporter exporter = useExporter( engine, outputType, servletResponse );
    exporter.export( servletResponse.getOutputStream(), engine.getCdaList() );
  }

  @GET
  @Path("/clearCache")
  @Produces("text/plain")
  @Consumes({ APPLICATION_XML, APPLICATION_JSON })
  public void clearCache(@Context HttpServletResponse servletResponse, 
                         @Context HttpServletRequest servletRequest) throws Exception
  {
    // Check if user is admin
    Boolean accessible = SecurityHelper.getInstance().isPentahoAdministrator(getPentahoSession());
    if(!accessible){
      String msg = "Method clearCache not exposed or user does not have required permissions."; 
      logger.error(msg);
      servletResponse.sendError(HttpServletResponse.SC_FORBIDDEN, msg);
    }
    
    CdaEngine.getInstance().getSettingsManager().clearCache();
    AbstractDataAccess.clearCache();

    servletResponse.getOutputStream().write("Cache cleared".getBytes());
  }

  @GET
  @Path("/editFile")
  @Produces( MimeTypes.HTML )
  public String editFile( @QueryParam("path") String path ) throws WebApplicationException, IOException {
    if (StringUtils.isEmpty( path )) {
      throw new WebApplicationException( 400 );
    }
    return getExtEditor().getMainEditor();
  }
  @GET
  @Path("/extEditor")
  @Produces( MimeTypes.HTML )
  public String getExtEditor( @QueryParam("path") String path ) throws WebApplicationException, IOException {
    if (StringUtils.isEmpty( path )) {
      throw new WebApplicationException( 400 );
    }
    return getExtEditor().getExtEditor();
  }

  public void editFile(@Context HttpServletResponse servletResponse, 
                       @Context HttpServletRequest servletRequest) throws IOException
  {
    String path = getPath( servletRequest );
    servletResponse.sendRedirect(
        PluginEnvironment.env().getUrlProvider().getPluginBaseUrl() + "editFile?path=" + path );
  }

  /**
   * called by content generator
   */
  public void previewQuery(@Context HttpServletRequest servletRequest,
                           @Context HttpServletResponse servletResponse) throws Exception
  {
    String path = getPath( servletRequest );
    servletResponse.sendRedirect(
        PluginEnvironment.env().getUrlProvider().getPluginBaseUrl() + "previewQuery?path=" + path );
    //writeOut( servletResponse.getOutputStream(), previewQuery( servletRequest ) );
  }

  private CacheManager getCacheManager() {
    return new CacheManager( PluginEnvironment.env().getUrlProvider(), CdaEngine.getEnvironment().getRepo() );
  }

  @GET
  @Path("/previewQuery")
  @Produces( MimeTypes.HTML )
  public String previewQuery(@Context HttpServletRequest servletRequest) throws Exception
  {
    return getPreviewer().previewQuery( getPath( servletRequest ) );
  }

  private String getPath( HttpServletRequest servletRequest ) {
      String path = servletRequest.getParameter( "path" );
      if (!StringUtils.isEmpty( path ) ) {
        return path;
      }
      String uri = servletRequest.getRequestURI();
      Matcher pathFinder = CDA_PATH.matcher( uri );
      if ( pathFinder.lookingAt() ) {
        path = pathFinder.group( 1 );
        return path.replaceAll( ":", "/" );
      }
      return null;
  }
  private Previewer getPreviewer() {
    return new Previewer( PluginEnvironment.env().getUrlProvider(), CdaEngine.getEnvironment().getRepo() );
  }
  private ExtEditor getExtEditor() {
    return new ExtEditor( PluginEnvironment.env().getUrlProvider(), CdaEngine.getEnvironment().getRepo() );
  }

  /**
   * For CDE discovery
   */
  @GET
  @Path("/listDataAccessTypes")
  @Produces(APPLICATION_JSON)
  @Consumes({ APPLICATION_XML, APPLICATION_JSON })
  public String listDataAccessTypes(@DefaultValue("false") @QueryParam("refreshCache") Boolean refreshCache) throws Exception
  {
    DataAccessConnectionDescriptor[] data = CdaEngine.getInstance().getSettingsManager().getDataAccessDescriptors(refreshCache);

    StringBuilder output = new StringBuilder("");
    if (data != null)
    {
      output.append("{\n");
      for (DataAccessConnectionDescriptor datum : data)
      {
        output.append(datum.toJSON() + ",\n");
      }
      return output.toString().replaceAll(",\n\\z", "\n}");
    }
    else return "";//XXX
  }

  @GET
  @Path("/manageCache")
  @Produces( MimeTypes.HTML )
  public String manageCache() throws Exception
  {
    return getCacheManager().manageCache();
  }

  private IPentahoSession getPentahoSession() {
    return PentahoSessionHolder.getSession();
  }

  protected void writeOut(OutputStream out, String contents) throws IOException {
      IOUtils.write(contents, out, getEncoding());
      out.flush();
    }

  private CdaCoreService getCdaCoreService() {
    return new CdaCoreService( CdaEngine.getInstance() );
  }


}
