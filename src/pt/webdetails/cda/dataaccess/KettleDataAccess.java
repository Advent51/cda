
package pt.webdetails.cda.dataaccess;

import org.dom4j.Element;
import org.pentaho.reporting.engine.classic.core.DataFactory;
import org.pentaho.reporting.engine.classic.extensions.datasources.kettle.KettleDataFactory;
import org.pentaho.reporting.libraries.resourceloader.ResourceKey;
import org.pentaho.reporting.libraries.resourceloader.ResourceKeyCreationException;
import org.pentaho.reporting.libraries.resourceloader.ResourceManager;

import pt.webdetails.cda.connections.ConnectionCatalog.ConnectionType;
import pt.webdetails.cda.connections.InvalidConnectionException;
import pt.webdetails.cda.connections.kettle.KettleConnection;
import pt.webdetails.cda.settings.UnknownConnectionException;

/**
 * Todo: Document me!
 * <p/>
 * Date: 16.02.2010
 * Time: 13:20:39
 *
 * @author Thomas Morgner.
 */
public class KettleDataAccess extends PREDataAccess
{
  
  private ResourceKey fileKey;

  public KettleDataAccess(final Element element)
  {
    super(element);
  }

  public KettleDataAccess()
  {
    super();
  }

  public DataFactory getDataFactory() throws UnknownConnectionException, InvalidConnectionException
  {
    final KettleConnection connection = (KettleConnection) getCdaSettings().getConnection(getConnectionId());

    final KettleDataFactory dataFactory = new KettleDataFactory();
    dataFactory.setQuery("query", connection.createTransformationProducer(getQuery()));
    return dataFactory;
  }

  public String getType()
  {
    return "kettle";
  }

  @Override
  public ConnectionType getConnectionType()
  {
    return ConnectionType.KETTLE;
  }
  
  @Override 
  public void setCdaSettings(pt.webdetails.cda.settings.CdaSettings cdaSettings) {
    super.setCdaSettings(cdaSettings);
    final ResourceManager resourceManager = new ResourceManager();
    resourceManager.registerDefaults();
    try {
      fileKey = resourceManager.deriveKey(getCdaSettings().getContextKey(), "");
    } catch (ResourceKeyCreationException e) {
      fileKey = null;//will blow down the road
    }
  };
  
  protected ResourceKey getResourceKey(){
    return fileKey;
  }
  
  /**
   * ContextKey is used to resolve the transformation file, and so must be stored in the cache key.
   */
  @Override
  public ResourceKey getExtraCacheKey(){
    return getResourceKey();
  }
}
