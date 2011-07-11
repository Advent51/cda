package pt.webdetails.cda.dataaccess;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.swing.table.TableModel;

import net.sf.ehcache.Cache;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Element;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pentaho.reporting.engine.classic.core.ParameterDataRow;

import pt.webdetails.cda.CdaBoot;
import pt.webdetails.cda.connections.Connection;
import pt.webdetails.cda.connections.ConnectionCatalog;
import pt.webdetails.cda.connections.DummyConnection;
import pt.webdetails.cda.exporter.ExporterException;
import pt.webdetails.cda.exporter.JsonExporter;
import pt.webdetails.cda.query.QueryOptions;
import pt.webdetails.cda.settings.UnknownConnectionException;
import pt.webdetails.cda.utils.TableModelUtils;
import pt.webdetails.cda.utils.Util;

/**
 * Implementation of the SimpleDataAccess
 * User: pedro
 * Date: Feb 3, 2010
 * Time: 11:04:10 AM
 */
public abstract class SimpleDataAccess extends AbstractDataAccess
{

  protected static class TableCacheKey implements Serializable
  {

    private static final long serialVersionUID = 2L; //1->2 only hash of connection kept
    
    private int connectionHash;
    private String query;
    private ParameterDataRow parameterDataRow;
    private Object extraCacheKey;


    /**
     * For serialization
     */
    protected TableCacheKey()
    {
    }


    private void writeObject(java.io.ObjectOutputStream out) throws IOException
    {
      out.writeInt(connectionHash);
      out.writeObject(query);
      out.writeObject(createParametersFromParameterDataRow(parameterDataRow));
      out.writeObject(extraCacheKey);
    }


    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException
    {
      //connection
      connectionHash = in.readInt();
      //query
      query = (String) in.readObject();
      //parameterDataRow
      try
      {
        Object holder = in.readObject();
        if (holder != null)
        {
          parameterDataRow = createParameterDataRowFromParameters((ArrayList<Parameter>) (ArrayList) holder);
        }
        else
        {
          parameterDataRow = null;
        }
      }
      catch (InvalidParameterException e)
      {
        parameterDataRow = null;
      }
      //extraCacheKey
      extraCacheKey = in.readObject();
    }


    private TableCacheKey(final Connection connection, final String query,
            final ParameterDataRow parameterDataRow, final Object extraCacheKey)
    {
      if (connection == null)
      {
        throw new NullPointerException();
      }
      if (query == null)
      {
        throw new NullPointerException();
      }
      if (parameterDataRow == null)
      {
        throw new NullPointerException();
      }

      this.connectionHash = connection.hashCode();
      this.query = query;
      this.parameterDataRow = parameterDataRow;
      this.extraCacheKey = extraCacheKey;
    }


    public boolean equals(final Object o)
    {
      if (this == o)
      {
        return true;
      }
      if (o == null || getClass() != o.getClass())
      {
        return false;
      }

      final TableCacheKey that = (TableCacheKey) o;

      if(connectionHash != that.connectionHash){
        return false;
      }
      if (parameterDataRow != null ? !parameterDataRow.equals(that.parameterDataRow) : that.parameterDataRow != null)
      {
        return false;
      }
      if (query != null ? !query.equals(that.query) : that.query != null)
      {
        return false;
      }
      if (extraCacheKey != null ? !extraCacheKey.equals(that.extraCacheKey) : that.extraCacheKey != null)
      {
        return false;
      }

      return true;
    }


    @Override
    public int hashCode()
    {
      int result = connectionHash;
      result = 31 * result + (query != null ? query.hashCode() : 0);
      result = 31 * result + (parameterDataRow != null ? parameterDataRow.hashCode() : 0);
      result = 31 * result + (extraCacheKey != null ? extraCacheKey.hashCode() : 0);
      return result;
    }
  }
  private static final Log logger = LogFactory.getLog(SimpleDataAccess.class);
  protected String connectionId;
  protected String query;
  private static final String QUERY_TIME_THRESHOLD_PROPERTY = "pt.webdetails.cda.QueryTimeThreshold";
  private static int queryTimeThreshold = getQueryTimeThresholdFromConfig(3600);//seconds


  public SimpleDataAccess()
  {
  }


  public SimpleDataAccess(final Element element)
  {

    super(element);
    connectionId = element.attributeValue("connection");
    query = element.selectSingleNode("./Query").getText();

  }


  /**
   * 
   * @param id
   * @param name
   * @param connectionId
   * @param query
   */
  public SimpleDataAccess(String id, String name, String connectionId, String query)
  {
    super(id, name);
    this.query = query;
    this.connectionId = connectionId;
  }


  protected synchronized TableModel queryDataSource(final QueryOptions queryOptions) throws QueryException
  {

    final Cache cache = getCache();

    // Get parameters from definition and apply it's values
    final ArrayList<Parameter> parameters = (ArrayList<Parameter>) getParameters().clone();

    for (final Parameter parameter : parameters)
    {
      final Parameter parameterPassed = queryOptions.getParameter(parameter.getName());
      if (parameter.getAccess().equals(Parameter.Access.PUBLIC) && parameterPassed != null)
      {
        //parameter.setStringValue(parameterPassed.getStringValue());
        try
        {
          parameterPassed.inheritDefaults(parameter);
          parameter.setValue(parameterPassed.getValue());
        }
        catch (InvalidParameterException e){
          throw new QueryException("Error parsing parameters ", e);
        }
      }
      else
      {
        parameter.setValue(parameter.getDefaultValue());
      }
    }


    final ParameterDataRow parameterDataRow;
    try
    {
      parameterDataRow = createParameterDataRowFromParameters(parameters);
    }
    catch (InvalidParameterException e)
    {
      throw new QueryException("Error parsing parameters ", e);
    }

    // create the cache-key which is both query and parameter values
    TableCacheKey key;
    TableModel tableModelCopy;
    try
    {
      try
      {
        final Connection connection;
        if (getConnectionType() == ConnectionCatalog.ConnectionType.NONE)
        {
          connection = new DummyConnection();
        }
        else
        {
          connection = getCdaSettings().getConnection(getConnectionId());
        }
        key = new TableCacheKey(connection, getQuery(), parameterDataRow, getExtraCacheKey());
      }
      catch (UnknownConnectionException e)
      {
        // I'm sure I'll never be here
        throw new QueryException("Unable to get a Connection for this dataAccess ", e);
      }

      if (isCache())
      {
        ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
        try{
          //make sure we have the right class loader in thread to instantiate cda classes in case DiskStore is used
          Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
          final net.sf.ehcache.Element element = cache.get(key);
          if (element != null && !queryOptions.isCacheBypass()) // Are we explicitly saying to bypass the cache?
          {
            final TableModel cachedTableModel = (TableModel) element.getObjectValue();
            if (cachedTableModel != null)
            {
              // we have a entry in the cache ... great!
              logger.debug("Found tableModel in cache. Returning");
              return cachedTableModel;
            }
          }
        }
        catch(Exception e){
          logger.error("Error while attempting to load from cache, bypassing cache (cause: " + e.getClass() + ")", e);
        }
        finally{
          Thread.currentThread().setContextClassLoader(contextCL);
        }
      }

      //start timing query
      long beginTime = System.currentTimeMillis();

      final TableModel tableModel = postProcessTableModel(performRawQuery(parameterDataRow));

      logIfDurationAboveThreshold(beginTime, getId(), getQuery(), parameters);

      // Copy the tableModel and cache it
      // Handle the TableModel

      tableModelCopy = TableModelUtils.getInstance().copyTableModel(this, tableModel);
    }
    catch (Exception e)
    {
      throw new QueryException("Found an unhandled exception:", e);
    }
    finally
    {
      closeDataSource();
    }

    // put the copy into the cache ...
    if (isCache())
    {
      final net.sf.ehcache.Element storeElement = new net.sf.ehcache.Element(key, tableModelCopy);
      storeElement.setTimeToLive(getCacheDuration());
      cache.put(storeElement);
      cache.flush();
      
      // Print cache status size
      logger.debug("Cache status: " + cache.getMemoryStoreSize() + " in memory, " + 
              cache.getDiskStoreSize() + " in disk");
    }

    // and finally return the copy.
    return tableModelCopy;
  }


  /**
   * @param parameters
   * @param beginTime
   */
  private void logIfDurationAboveThreshold(final long beginTime, final String queryId, final String query, final ArrayList<Parameter> parameters)
  {
    long endTime = System.currentTimeMillis();
    long duration = (endTime - beginTime) / 1000;//precision not an issue: integer op is ok
    if (duration > queryTimeThreshold)
    {
      //log query and duration
      String logMsg = "Query " + queryId + " took " + duration + "s.\n";
      logMsg += "\t Query contents: << " + query.trim() + " >>\n";
      if (parameters.size() > 0)
      {
        logMsg += "\t Parameters: \n";
        for (Parameter parameter : parameters)
        {
          logMsg += "\t\t" + parameter.toString() + "\n";
        }
      }
      logger.debug(logMsg);
    }
  }


  private static ParameterDataRow createParameterDataRowFromParameters(final ArrayList<Parameter> parameters) throws InvalidParameterException
  {

    final ArrayList<String> names = new ArrayList<String>();
    final ArrayList<Object> values = new ArrayList<Object>();

    for (final Parameter parameter : parameters)
    {
      names.add(parameter.getName());
      values.add(parameter.getValue());
    }

    final ParameterDataRow parameterDataRow = new ParameterDataRow(names.toArray(new String[]
            {
            }), values.toArray());

    return parameterDataRow;

  }


  /**
   * for serialization
   **/
  private static ArrayList<Parameter> createParametersFromParameterDataRow(final ParameterDataRow row)
  {
    ArrayList<Parameter> parameters = new ArrayList<Parameter>();
    if(row != null) for (String name : row.getColumnNames())
    {
      Object value = row.get(name);
      Parameter param = new Parameter(name, value != null ? value.toString() : null);
      Parameter.Type type = Parameter.Type.inferTypeFromObject(value);
      param.setType(type);
      parameters.add(param);
    }
    return parameters;
  }


  protected TableModel postProcessTableModel(TableModel tm)
  {
    // we can use this method to override the general behavior. By default, no post processing is done
    return tm;
  }


  /**
   * Extra arguments to be used for the cache key. Defaults to null but classes that
   * extend SimpleDataAccess may decide to implement it
   * @return
   */
  protected Object getExtraCacheKey()
  {
    return null;
  }


  protected abstract TableModel performRawQuery(ParameterDataRow parameterDataRow) throws QueryException;


  public abstract void closeDataSource() throws QueryException;


  public String getQuery()
  {
    return query;
  }


  public String getConnectionId()
  {
    return connectionId;
  }


  @Override
  public ArrayList<PropertyDescriptor> getInterface()
  {
    ArrayList<PropertyDescriptor> properties = super.getInterface();
    properties.add(new PropertyDescriptor("query", PropertyDescriptor.Type.STRING, PropertyDescriptor.Placement.CHILD));
    properties.add(new PropertyDescriptor("connection", PropertyDescriptor.Type.STRING, PropertyDescriptor.Placement.ATTRIB));
    properties.add(new PropertyDescriptor("cache", PropertyDescriptor.Type.BOOLEAN, PropertyDescriptor.Placement.ATTRIB));
    properties.add(new PropertyDescriptor("cacheDuration", PropertyDescriptor.Type.NUMERIC, PropertyDescriptor.Placement.ATTRIB));
    return properties;
  }


  private static int getQueryTimeThresholdFromConfig(int defaultValue)
  {
    String strVal = CdaBoot.getInstance().getGlobalConfig().getConfigProperty(QUERY_TIME_THRESHOLD_PROPERTY);
    if (!Util.isNullOrEmpty(strVal))
    {
      try
      {
        return Integer.parseInt(strVal);
      }
      catch (NumberFormatException nfe)
      {
      }//ignore, use default
    }
    return defaultValue;
  }

  
  public static JSONObject listQueriesInCache() throws JSONException, ExporterException {
    
    JSONArray results = new JSONArray();
    
    Cache cdaCache = AbstractDataAccess.getCache();
    
    for(Object key : cdaCache.getKeys()) {
      
      if(key instanceof TableCacheKey){
        
        JSONObject queryInfo = new JSONObject();
        
        TableCacheKey cacheKey = (TableCacheKey) key;
        
        //query
        queryInfo.put("query", cacheKey.query);
        //parameters
        ParameterDataRow prow = cacheKey.parameterDataRow;
        JSONObject parameters = new JSONObject();
        for(String paramName : prow.getColumnNames()){
          parameters.put(paramName, prow.get(paramName));
        }
        queryInfo.put("parameters", parameters);
        
        //cacheKey.query;
        net.sf.ehcache.Element elem = cdaCache.getQuiet(key);
                
        //inserted
        queryInfo.put("inserted", elem.getLatestOfCreationAndUpdateTime());
        queryInfo.put("accessed", elem.getLastAccessTime());
        queryInfo.put("hits", elem.getHitCount());
        
        //query results
        JsonExporter exporter = new JsonExporter(null);
        queryInfo.put("table", exporter.getTableAsJson((TableModel) elem.getObjectValue()));//TODO: move elsewhere
        
        results.put(queryInfo);
      }
      else {
        logger.warn("Found non-TableCacheKey object in cache, skipping...");
      }
    }
    
    JSONObject result = new JSONObject();
    result.put("results", results);
    result.put("cacheLength", cdaCache.getSize());
    result.put("memoryStoreLength", cdaCache.getMemoryStoreSize());
    result.put("diskStoreLength", cdaCache.getDiskStoreSize());
    return result;
  }
  
  
}
