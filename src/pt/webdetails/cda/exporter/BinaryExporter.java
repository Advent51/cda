/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
package pt.webdetails.cda.exporter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import javax.swing.table.TableModel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import pt.webdetails.cda.utils.MetadataTableModel;

/**
 * JsonExporter
 * <p/>
 * User: pedro Date: Feb 5, 2010 Time: 5:07:12 PM
 */
public class BinaryExporter extends AbstractExporter
{

  public static final String MYMETYPE_SETTING = "mimeType";
  
  private String attachmentName;
  private String mimeType;


  public BinaryExporter()
  {
    super();
  }


  public BinaryExporter(HashMap<String, String> extraSettings)
  {
    super(extraSettings);
    mimeType = getSetting(MYMETYPE_SETTING, "octet-stream");
    attachmentName = extraSettings.get(ATTACHMENT_NAME_SETTING);
  }


  public void export(final OutputStream out, final TableModel tableModel) throws ExporterException
  {

      byte[] file = getBinaryFromFile(tableModel);
      
      if (file != null) 
        try {
          out.write(file);
        } catch (IOException ioe) {
          logger.error("Exception while writing blob to ouput stream", ioe);
        }

  }


  private byte[] getBinaryFromFile(TableModel tableModel) throws ExporterException {
    final int columnCount = tableModel.getColumnCount();
    
    int colIdx = -1, fileNameColIdx = -1;
    for (int i = 0; i < columnCount; i++)
    {           
      Class<?> columnClass = tableModel.getColumnClass(i);
      if (getColType(columnClass).equals("Blob")) {
        colIdx = i;
      }
      if (tableModel.getColumnName(i).equals("file_name"))
        fileNameColIdx = i;      
    }
    
    int rowCount = tableModel.getRowCount();
    
    if (rowCount > 0 && fileNameColIdx >= 0)
      attachmentName = (String) tableModel.getValueAt(0, fileNameColIdx);   
    
       
    if (colIdx >= 0) {      
      if (rowCount > 0) {
        Object value = tableModel.getValueAt(0, colIdx);        
        return (byte[])value;
      }
    } else {
      logger.warn("Did not find a blob column in the tableModel");
    }
    
    
    return null;
  }
  
  


  public String getMimeType()
  {
    return "application/" + mimeType;
  }


  public String getAttachmentName()
  {
    return attachmentName;
  }
}
