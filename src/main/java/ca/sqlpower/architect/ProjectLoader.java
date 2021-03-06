/*
 * Copyright (c) 2008, SQL Power Group Inc.
 *
 * This file is part of Power*Architect.
 *
 * Power*Architect is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Power*Architect is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ca.sqlpower.architect;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.swing.JOptionPane;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.beanutils.ConvertUtils;
import org.apache.commons.digester.AbstractObjectCreationFactory;
import org.apache.commons.digester.Digester;
import org.apache.commons.digester.Rule;
import org.apache.commons.digester.SetPropertiesRule;
import org.apache.log4j.Logger;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import ca.sqlpower.architect.ddl.GenericDDLGenerator;
import ca.sqlpower.architect.profile.ColumnProfileResult;
import ca.sqlpower.architect.profile.ColumnValueCount;
import ca.sqlpower.architect.profile.TableProfileResult;
import ca.sqlpower.sql.DataSourceCollection;
import ca.sqlpower.sql.JDBCDataSource;
import ca.sqlpower.sql.JDBCDataSourceType;
import ca.sqlpower.sql.SPDataSource;
import ca.sqlpower.sqlobject.SQLCatalog;
import ca.sqlpower.sqlobject.SQLColumn;
import ca.sqlpower.sqlobject.SQLDatabase;
import ca.sqlpower.sqlobject.SQLIndex;
import ca.sqlpower.sqlobject.SQLIndex.AscendDescend;
import ca.sqlpower.sqlobject.SQLIndex.Column;
import ca.sqlpower.sqlobject.SQLObject;
import ca.sqlpower.sqlobject.SQLObjectException;
import ca.sqlpower.sqlobject.SQLRelationship;
import ca.sqlpower.sqlobject.SQLRelationship.Deferrability;
import ca.sqlpower.sqlobject.SQLRelationship.SQLImportedKey;
import ca.sqlpower.sqlobject.SQLRelationship.UpdateDeleteRule;
import ca.sqlpower.sqlobject.SQLSchema;
import ca.sqlpower.sqlobject.SQLTable;
import ca.sqlpower.sqlobject.SQLTypePhysicalPropertiesProvider;
import ca.sqlpower.sqlobject.UserDefinedSQLType;
import ca.sqlpower.swingui.SPSUtils;
import ca.sqlpower.util.BrowserUtil;
import ca.sqlpower.util.DefaultUserPrompterFactory;
import ca.sqlpower.util.UserPrompter;
import ca.sqlpower.util.UserPrompter.UserPromptOptions;
import ca.sqlpower.util.UserPrompter.UserPromptResponse;
import ca.sqlpower.util.UserPrompterFactory.UserPromptType;
import ca.sqlpower.xml.UnescapingSaxParser;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

public class ProjectLoader {

    /*
     * Any Jakarta Commons BeanUtils converters needed by the Digester should
     * be registered here.  This guarantees they will be registered before
     * they're needed, and that they won't be registered more than once.
     */
    static {
        ConvertUtils.register(new DeferrabilityConverter(), Deferrability.class);
        ConvertUtils.register(new UpdateDeleteRuleConverter(), UpdateDeleteRule.class);
        ConvertUtils.register(new AscendDescendConverter(), AscendDescend.class);
    }

    /**
     * This will load the attributes in all SQLObjects that are not loaded by basic
     * setters through the digester.
     */
    private static void LoadSQLObjectAttributes(SQLObject obj, Attributes attr) {
        String message = attr.getValue("sql-exception");
        if (message != null) {
            try {
                obj.setChildrenInaccessibleReason(new SQLObjectException(message), SQLObject.class, false);
            } catch (SQLObjectException e) {
                throw new AssertionError("Unreachable code");
            }
        }
    }

    //  ---------------- persistent properties -------------------

    protected File file;

    // ------------------ load and save support -------------------

    private static final Logger logger = Logger.getLogger(ProjectLoader.class);

    /**
     * Tracks whether or not this project has been modified since last saved.
     */
    protected boolean modified;

    /**
     * Don't let application exit while saving.
     */
    protected boolean saveInProgress;

    /**
     * @return Returns the saveInProgress.
     */
    public boolean isSaveInProgress() {
        return saveInProgress;
    }
    /**
     * @param saveInProgress The saveInProgress to set.
     */
    public void setSaveInProgress(boolean saveInProgress) {
        this.saveInProgress = saveInProgress;
    }
    /**
     * Should be set to NULL unless we are currently saving the
     * project, at which time it's writing to the project file.
     */
    protected PrintWriter out;

    /**
     * This map maps String ID codes to SQLObject instances used in loading.
     */
    protected Map<String, SQLObject> sqlObjectLoadIdMap;

    /**
     * This holds mappings from SQLObject instance to String ID used in saving.
     */
    protected Map<SQLObject, String> sqlObjectSaveIdMap;

    /**
     * This map maps String ID codes to DBCS instances used in loading.
     */
    protected Map<String, JDBCDataSource> dbcsLoadIdMap;

    /**
     * This holds mappings from DBCS instance to String ID used in saving.
     */
    protected Map<SPDataSource, String> dbcsSaveIdMap;

    /**
     * The last value we sent to the progress monitor.
     */
    protected int progress = 0;

    protected ArchitectSession session;
    
    /**
     * The session that will display any required popups.
     */
    protected ArchitectSession siblingSession;

    /**
     * This stores the version of the file that this project loader would
     * overwrite on save. If the user is overwriting a file that is not the same
     * version as the Architect that they are using they should be prompted.
     * This will be null if the current project was not loaded or saved (ie: it
     * is new).
     */
    protected String fileVersion;

    public ProjectLoader(ArchitectSession session) {
        this.session = session;
    }


    // ------------- READING THE PROJECT FILE ---------------

    public void load(InputStream in, DataSourceCollection<? extends SPDataSource> dataSources) throws IOException, SQLObjectException {
        load(in, dataSources, null);
    }
    
    /**
     * Loads the project data from the given input stream.
     * <p>
     * Note: the input stream is always closed afterwards.
     *
     * @param in
     *            Used to load in the project data, must support mark.
     * @param dataSources
     *            Collection of the data sources used in the project
     */
    public void load(InputStream in, DataSourceCollection<? extends SPDataSource> dataSources,
            ArchitectSession messageDelegate) throws IOException, SQLObjectException {
        UnclosableInputStream uin = new UnclosableInputStream(in);
        siblingSession = messageDelegate;
        try {
            dbcsLoadIdMap = new HashMap<String, JDBCDataSource>();
            sqlObjectLoadIdMap = new HashMap<String, SQLObject>();

            Digester digester = null;

            // use digester to read from file
            try {
                digester = setupDigester();
                digester.parse(uin);
            } catch (SAXException ex) {
                //The digester likes to wrap the cancelled exception in a SAXException.
                if (ex.getException() instanceof DigesterCancelledException) {
                    //Digeseter was cancelled by the user. Do not load anything.
                    throw new RuntimeException(new InterruptedIOException("progress"));
                }
                logger.error("SAX Exception in project file parse!", ex);
                String message;
                if (digester == null) {
                    message = "Couldn't create an XML parser";
                } else {
                    message = "There is an XML parsing error in project file at Line:" +
                    digester.getDocumentLocator().getLineNumber() + " Column:" +
                    digester.getDocumentLocator().getColumnNumber();
                }
                throw new SQLObjectException(message, ex);
            } catch (IOException ex) {
                logger.error("IO Exception in project file parse!", ex);
                throw new SQLObjectException("There was an I/O error while reading the file", ex);
            } catch (Exception ex) {
                logger.error("General Exception in project file parse!", ex);
                throw new SQLObjectException("Unexpected Exception", ex);
            }

            SQLObject dbConnectionContainer = ((SQLObject) getSession().getRootObject());

            // hook up data source parent types
            for (SQLDatabase db : dbConnectionContainer.getChildren(SQLDatabase.class)) {
                JDBCDataSource ds = db.getDataSource();
                String parentTypeId = ds.getPropertiesMap().get(JDBCDataSource.DBCS_CONNECTION_TYPE);
                if (parentTypeId != null) {
                    for (JDBCDataSourceType dstype : dataSources.getDataSourceTypes()) {
                        if (dstype.getName().equals(parentTypeId)) {
                            ds.setParentType(dstype);
                            // TODO unit test that this works
                        }
                    }
                    if (ds.getParentType() == null) {
                        logger.error("Data Source \""+ds.getName()+"\" has type \""+parentTypeId+"\", which is not configured in the user prefs.");
                        // TODO either reconstruct the parent type, or bring this problem to the attention of the user.
                        // TODO test this
                    } else {
                        // TODO test that the referenced parent type is properly configured (has a driver, etc)
                        // TODO test for this behaviour
                    }
                }

            }

            /*
             * for backward compatibilty, in the old project file, we have
             * primaryKeyName in the table attrbute, but nothing
             * in the sqlIndex that indicates primary key index,
             * so, we have to set the index as primary key index
             * if the index name == table.primaryKeyName after load the project,
             * table.primaryKeyName is save in the map now, not in the table object
             */
            for (SQLTable table : (List<SQLTable>)getSession().getTargetDatabase().getTables()) {

                if (logger.isDebugEnabled()) {
                    if (!table.isPopulated()) {
                        logger.debug("Table ["+table.getName()+"] not populated");
                    } else {
                        logger.debug("Table ["+table.getName()+"] index folder contents: "+table.getIndices());
                    }
                }

                if ( table.getPrimaryKeyIndex() == null) {
                    logger.debug("primary key index is null in table: " + table);
                    logger.debug("number of children found in indices folder: " + table.getIndices().size());
                    for (SQLIndex index : table.getIndices()) {
                        if (sqlObjectLoadIdMap.get(table.getName()+"."+index.getName()) != null) {
                            table.getPrimaryKeyIndex().updateToMatch(index);
                            break;
                        }
                    }
                }
                logger.debug("Table ["+table.getName()+"]2 index folder contents: "+table.getIndices());
                logger.debug("Table ["+table.getName()+"]3 index folder contents: "+table.getIndices());

                if (logger.isDebugEnabled()) {
                    if (!table.isPopulated()) {
                        logger.debug("Table ["+table.getName()+"] not populated");
                    } else {
                        logger.debug("Table ["+table.getName()+"] index folder contents: "+table.getIndices().size());
                    }
                }

            }

            /*
             * In old versions of the architect, user defined types weren't
             * available, so all columns stored their type as a JDBC type code.
             * For all columns in the playpen, we need to hook up upstream user
             * defined types.
             */
            ListMultimap<String, SQLColumn> columns = ArrayListMultimap.create();
            for (SQLTable table : getSession().getTargetDatabase().getTables()) {
                for (SQLColumn column : table.getChildren(SQLColumn.class)) {
                    SQLColumn sourceColumn = column.getSourceColumn();
                    if (sourceColumn != null && sourceColumn.getPlatform() != null) {
                        columns.put(column.getSourceColumn().getPlatform(), column);
                    } else {
                        columns.put(SQLTypePhysicalPropertiesProvider.GENERIC_PLATFORM, column);
                    }
                }
            }
            for (String platform : columns.keySet()) {
                SQLColumn.assignTypes(columns.get(platform), dataSources, platform, new DefaultUserPrompterFactory());
            }

            setModified(false);
        } finally {
            uin.forceClose();
        }
    }

    protected Digester setupDigester() throws ParserConfigurationException, SAXException {
        Digester d = new Digester(new UnescapingSaxParser());
        final ArchitectSession messageOwner = (siblingSession == null ? session : siblingSession);
        d.setValidating(false);
        d.push(session);

        d.addRule("architect-enterprise-project", new Rule() {
            @Override
            public void begin(String namespace, String name, Attributes attributes) throws Exception {
                UserPrompter loadingWarningPrompt = messageOwner.createUserPrompter(
                        "This file contains an Enterprise project and can only\n" + 
                        "be opened in the Architect Enterprise Edition.",
                        UserPromptType.BOOLEAN, UserPromptOptions.OK_CANCEL,
                        UserPromptResponse.CANCEL, UserPromptResponse.CANCEL, "Get Enterprise", "Cancel");
                UserPromptResponse upr = loadingWarningPrompt.promptUser();
                if (upr == UserPromptResponse.OK) {
                    try {
                        BrowserUtil.launch("http://www.sqlpower.ca/page/architect-e");
                    } catch (IOException e) {
                        throw new DigesterCancelledException();
                    }
                }
                throw new DigesterCancelledException();
            }
        });
        
        //app version number
        d.addRule("architect-project", new Rule() {
            @Override
            public void begin(String namespace, String name, Attributes attributes) throws Exception {
                fileVersion = attributes.getValue("appversion");
                String loadingMessage;
                try {
                    if (fileVersion == null) {
                        loadingMessage = "The version of the file cannot be found.";
                        fileVersion = "0";
                    } else if (ArchitectVersion.APP_FULL_VERSION.compareTo(
                            new ArchitectVersion(fileVersion)) < 0) {
                        loadingMessage = "This file was last saved with a newer version.\n" +
                        		"Loading with an older version may cause data loss.";
                    } else {
                        return;
                    }
                } catch (Exception e) {
                    loadingMessage = "The version of the file cannot be understood.";
                }
                UserPrompter loadingWarningPrompt = messageOwner.createUserPrompter(
                        loadingMessage + "\nDo you wish to try and open the file?",
                        UserPromptType.BOOLEAN, UserPromptOptions.OK_NOTOK_CANCEL,
                        UserPromptResponse.OK, UserPromptResponse.OK, "Try loading",
                        "Upgrade...", "Cancel");
                UserPromptResponse response = loadingWarningPrompt.promptUser();
                if (response == UserPromptResponse.OK) {
                    //continue to try loading
                } else if (response == UserPromptResponse.NOT_OK) {
                    BrowserUtil.launch(SPSUtils.SQLP_ARCHITECT_URL);
                    throw new DigesterCancelledException();
                } else if (response == UserPromptResponse.CANCEL) {
                    throw new DigesterCancelledException();
                }
            }
        });

        // project name
        d.addCallMethod("architect-project/project-name", "setName", 0); // argument is element body text

        // source DB connection specs (deprecated in favour of project-data-sources; this is only here for backward compatibility)
        DBCSFactory dbcsFactory = new DBCSFactory();
        d.addFactoryCreate("architect-project/project-connection-specs/dbcs", dbcsFactory);
        d.addSetProperties
        ("architect-project/project-connection-specs/dbcs",
                new String[] {"connection-name", "driver-class", "jdbc-url", "user-name",
                "user-pass", "sequence-number", "single-login"},
                new String[] {"displayName", "driverClass", "url", "user",
                "pass", "seqNo", "singleLogin"});
        d.addCallMethod("architect-project/project-connection-specs/dbcs", "setName", 0);
        // these instances get picked out of the dbcsIdMap by the SQLDatabase factory

        // project data sources (replaces project connection specs)
        d.addFactoryCreate("architect-project/project-data-sources/data-source", dbcsFactory);
        d.addCallMethod("architect-project/project-data-sources/data-source/property", "put", 2);
        d.addCallParam("architect-project/project-data-sources/data-source/property", 0, "key");
        d.addCallParam("architect-project/project-data-sources/data-source/property", 1, "value");
        // for the project-data-sources, these instances get picked out of the dbcsIdMap by the SQLDatabase factory

        // but for the create kettle job settings, we add them explicitly


        // source database hierarchy
        d.addObjectCreate("architect-project/source-databases", LinkedList.class);
        d.addSetNext("architect-project/source-databases", "setSourceDatabaseList");

        SQLDatabaseFactory dbFactory = new SQLDatabaseFactory();
        d.addFactoryCreate("architect-project/source-databases/database", dbFactory);
        d.addSetProperties("architect-project/source-databases/database");
        d.addSetNext("architect-project/source-databases/database", "add");

        d.addObjectCreate("architect-project/source-databases/database/catalog", SQLCatalog.class);
        d.addSetProperties("architect-project/source-databases/database/catalog");
        d.addSetNext("architect-project/source-databases/database/catalog", "addChild");

        SQLSchemaFactory schemaFactory = new SQLSchemaFactory();
        d.addFactoryCreate("*/schema", schemaFactory);
        d.addSetProperties("*/schema");
        d.addSetNext("*/schema", "addChild");

        SQLTableFactory tableFactory = new SQLTableFactory();
        d.addFactoryCreate("*/table", tableFactory);
        d.addSetProperties("*/table");
        d.addCallMethod("*/remarks", "setRemarks", 0);
        d.addSetNext("*/table", "addChild");

        d.addFactoryCreate("*/folder", new SQLFolderFactory());

        SQLColumnFactory columnFactory = new SQLColumnFactory();
        d.addFactoryCreate("*/column", columnFactory);
        d.addSetProperties("*/column");
        d.addCallMethod("*/remarks", "setRemarks", 0);
        // this needs to be manually set last to prevent generic types
        // from overwriting database specific types

        // Old name (it has been updated to sourceDataTypeName)
        d.addCallMethod("*/column","setSourceDataTypeName",1);
        d.addCallParam("*/column",0,"sourceDBTypeName");

        // new name
        d.addCallMethod("*/column","setSourceDataTypeName",1);
        d.addCallParam("*/column",0,"sourceDataTypeName");
        d.addSetNext("*/column", "addChild");

        SQLRelationshipFactory relationshipFactory = new SQLRelationshipFactory();
        d.addFactoryCreate("*/relationship", relationshipFactory);
        d.addSetProperties("*/relationship");
        // the factory adds the relationships to the correct PK and FK tables

        ColumnMappingFactory columnMappingFactory = new ColumnMappingFactory();
        d.addFactoryCreate("*/column-mapping", columnMappingFactory);
        d.addSetProperties("*/column-mapping");
        d.addSetNext("*/column-mapping", "addChild");

        SQLIndexFactory indexFactory = new SQLIndexFactory();
        d.addFactoryCreate("*/index", indexFactory);
        d.addSetProperties("*/index");
        d.addSetNext("*/index", "addChild");

        SQLIndexColumnFactory indexColumnFactory = new SQLIndexColumnFactory();
        d.addFactoryCreate("*/index-column", indexColumnFactory);
        d.addSetProperties("*/index-column");
        d.addSetNext("*/index-column", "addChild");

        SQLExceptionFactory exceptionFactory = new SQLExceptionFactory();
        d.addFactoryCreate("*/sql-exception", exceptionFactory);
        d.addSetProperties("*/sql-exception");
        d.addSetNext("*/sql-exception", "setChildrenInaccessibleReason");

        TargetDBFactory targetDBFactory = new TargetDBFactory();
        // target database hierarchy
        d.addFactoryCreate("architect-project/target-database", targetDBFactory);
        d.addSetProperties("architect-project/target-database");

        DDLGeneratorFactory ddlgFactory = new DDLGeneratorFactory();
        d.addFactoryCreate("architect-project/ddl-generator", ddlgFactory);
        d.addSetProperties("architect-project/ddl-generator");
        d.addSetNext("architect-project/ddl-generator", "setDDLGenerator");

        LiquibaseSettingsFactory lbFactory = new LiquibaseSettingsFactory();
        d.addFactoryCreate("architect-project/liquibase-settings", lbFactory);
        d.addSetProperties("architect-project/liquibase-settings");
        d.addSetNext("architect-project/liquibase-settings", "setLiquibaseSettings");

        ProfileManagerFactory profileManagerFactory = new ProfileManagerFactory();
        d.addFactoryCreate("*/profiles", profileManagerFactory);
        d.addSetProperties("*/profiles");

        /*
         * Backward compatibility: the table and column profiles used to be
         * stored as siblings to each other, with the parent of a column result
         * being the last table result that was read.
         */
        ProfileResultFactory profileResultFactory = new ProfileResultFactory();
        d.addFactoryCreate("*/profiles/profile-result", profileResultFactory);
        /*
         * backward compatibility: the exception property used to be a boolean, and now it's an actual exception.
         * this causes an IllegalArgumentException when parsing old files.
         * this workaround tells the digester not to auto-map the exception property.
         */
        d.addRule("*/profiles/profile-result", new SetPropertiesRule(new String[] {"exception"}, new String[] {}));
        d.addSetNext("*/profiles/profile-result", "loadResult");
        
        d.addFactoryCreate("*/profiles/table-profile-result", new TableProfileResultFactory());
        d.addRule("*/profiles/table-profile-result", new SetPropertiesRule(new String[] {"exception"}, new String[] {}));
        d.addSetNext("*/profiles/table-profile-result", "addTableProfileResult");
        
        d.addFactoryCreate("*/profiles/table-profile-result/column-profile-result", new ColumnProfileResultFactory());
        d.addRule("*/profiles/table-profile-result/column-profile-result", new SetPropertiesRule(new String[] {"exception"}, new String[] {}));
        d.addSetNext("*/profiles/table-profile-result/column-profile-result", "addColumnProfileResult");

        ProfileResultValueFactory profileResultValueFactory = new ProfileResultValueFactory();
        d.addFactoryCreate("*/profiles/table-profile-result/column-profile-result/avgValue", profileResultValueFactory );
        d.addSetNext("*/profiles/table-profile-result/column-profile-result/avgValue", "setAvgValue");
        d.addFactoryCreate("*/profiles/table-profile-result/column-profile-result/minValue", profileResultValueFactory);
        d.addSetNext("*/profiles/table-profile-result/column-profile-result/minValue", "setMinValue");
        d.addFactoryCreate("*/profiles/table-profile-result/column-profile-result/maxValue", profileResultValueFactory);
        d.addSetNext("*/profiles/table-profile-result/column-profile-result/maxValue", "setMaxValue");

        ProfileResultTopNValueFactory topNValueFactory = new ProfileResultTopNValueFactory();
        d.addFactoryCreate("*/profiles/table-profile-result/column-profile-result/topNvalue", topNValueFactory );
        d.addSetNext("*/profiles/table-profile-result/column-profile-result/topNvalue", "addValueCount");

        FileFactory fileFactory = new FileFactory();
        d.addFactoryCreate("*/file", fileFactory);
        d.addSetNext("*/file", "setFile");



        return d;
    }

    /**
     * Creates a SPDataSource object and puts a mapping from its
     * id (in the attributes) to the new instance into the dbcsIdMap.
     */
    private class DBCSFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) {
            JDBCDataSource dbcs = new JDBCDataSource(getSession().getDataSources());

            String id = attributes.getValue("id");
            if (id != null) {
                dbcsLoadIdMap.put(id, dbcs);
            } else {
                logger.info("No ID found in dbcs element while loading project! (this is normal for playpen db, but bad for other data sources!");
            }
            return dbcs;
        }
    }

    /**
     * Gets the playpen SQLDatabase instance.
     * Also attaches the DBCS referenced by the dbcsref attribute, if
     * there is such an attribute.
     * NOTE: this will only work until we support multiple playpens.
     */
    private class TargetDBFactory extends AbstractObjectCreationFactory {

        @Override
        public Object createObject(Attributes attributes) throws Exception {
            SQLDatabase ppdb = getSession().getTargetDatabase();

            String id = attributes.getValue("id");
            if (id != null) {
                sqlObjectLoadIdMap.put(id, ppdb);
            } else {
                logger.warn("No ID found in database element while loading project!");
            }

            String dbcsid = attributes.getValue("dbcs-ref");
            if (dbcsid != null) {
                ppdb.setDataSource(dbcsLoadIdMap.get(dbcsid));
            }

            sqlObjectLoadIdMap.put(id, ppdb);

            return ppdb;
        }

    }


    /**
     * Creates a SQLDatabase instance and adds it to the objectIdMap.
     * Also attaches the DBCS referenced by the dbcsref attribute, if
     * there is such an attribute.
     */
    private class SQLDatabaseFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) {
            SQLDatabase db = new SQLDatabase();

            String id = attributes.getValue("id");
            if (id != null) {
                sqlObjectLoadIdMap.put(id, db);
            } else {
                logger.warn("No ID found in database element while loading project!");
            }

            String dbcsid = attributes.getValue("dbcs-ref");
            if (dbcsid != null) {
                db.setDataSource(dbcsLoadIdMap.get(dbcsid));
            }

            String populated = attributes.getValue("populated");
            if (populated != null && populated.equals("false")) {
                db.setPopulated(false);
            }

            LoadSQLObjectAttributes(db, attributes);

            return db;
        }
    }

    /**
     * Creates a SQLSchema instance and adds it to the objectIdMap.
     */
    private class SQLSchemaFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) {
            boolean startPopulated;
            String populated = attributes.getValue("populated");
            startPopulated = (populated != null && populated.equals("true"));

            SQLSchema schema = new SQLSchema(startPopulated);
            String id = attributes.getValue("id");
            if (id != null) {
                sqlObjectLoadIdMap.put(id, schema);
            } else {
                logger.warn("No ID found in database element while loading project!");
            }

            LoadSQLObjectAttributes(schema, attributes);

            return schema;
        }
    }

    /**
     * The table most recently loaded from the project file.  The SQLFolderFactory
     * has to know which table it's creating a folder for, because it has to add
     * the folder upon creation instead of waiting for the digester to do it at the
     * end of the enclosing table element.
     */
    private SQLTable currentTable;

    /**
     * Creates a SQLTable instance and adds it to the objectIdMap.
     */
    private class SQLTableFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) throws SQLObjectException{
            SQLTable tab = new SQLTable();

            String id = attributes.getValue("id");
            String pkName = attributes.getValue("primaryKeyName");

            if (id != null) {
                sqlObjectLoadIdMap.put(id, tab);
                sqlObjectLoadIdMap.put(id+"."+pkName, tab);
            } else {
                logger.warn("No ID found in table element while loading project!");
            }

            String populated = attributes.getValue("populated");
            if (populated != null && populated.equals("false")) {
                tab.initFolders(false);
            }
            
            

            currentTable = tab;

            LoadSQLObjectAttributes(tab, attributes);

            return tab;
        }
    }

    /**
     * XXX Temporary factory for folders until the file format changes and the
     * folders are removed permanently.
     */
    private class SQLFolderFactory extends AbstractObjectCreationFactory {
        @Override
        public Object createObject(Attributes attributes) throws Exception {
            String type = attributes.getValue("type"); //1=col, 2=import, 3=export, 4=index
            boolean isPopulated = Boolean.valueOf(attributes.getValue("populated"));

            String message = attributes.getValue("sql-exception");

            if (type.equals("1")) {
                currentTable.setColumnsPopulated(isPopulated);
                if (message != null) {
                    try {
                        currentTable.setChildrenInaccessibleReason(new SQLObjectException(message),
                                SQLColumn.class, false);
                    } catch (SQLObjectException e) {
                        throw new AssertionError("Unreachable code");
                    }
                }
            } else if (type.equals("2")) {
                currentTable.setImportedKeysPopulated(isPopulated);
                if (message != null) {
                    try {
                        currentTable.setChildrenInaccessibleReason(new SQLObjectException(message),
                                SQLImportedKey.class, false);
                    } catch (SQLObjectException e) {
                        throw new AssertionError("Unreachable code");
                    }
                }
            } else if (type.equals("3")) {
                currentTable.setExportedKeysPopulated(isPopulated);
                if (message != null) {
                    try {
                        currentTable.setChildrenInaccessibleReason(new SQLObjectException(message),
                                SQLRelationship.class, false);
                    } catch (SQLObjectException e) {
                        throw new AssertionError("Unreachable code");
                    }
                }
            } else if (type.equals("4")) {
                currentTable.setIndicesPopulated(isPopulated);
                if (message != null) {
                    try {
                        currentTable.setChildrenInaccessibleReason(new SQLObjectException(message),
                                SQLIndex.class, false);
                    } catch (SQLObjectException e) {
                        throw new AssertionError("Unreachable code");
                    }
                }
            }

            return currentTable;
        }

    }

    /**
     * Creates a SQLColumn instance and adds it to the
     * objectIdMap. Also dereferences the source-column-ref attribute
     * if present.
     */
    private class SQLColumnFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) {
            SQLColumn col = new SQLColumn();

            String id = attributes.getValue("id");
            if (id != null) {
                sqlObjectLoadIdMap.put(id, col);
            } else {
                logger.warn("No ID found in column element while loading project!");
            }

            String sourceId = attributes.getValue("source-column-ref");
            if (sourceId != null) {
                col.setSourceColumn((SQLColumn) sqlObjectLoadIdMap.get(sourceId));
            }

            String sqlTypeUUID = attributes.getValue("userDefinedTypeUUID");
            UserDefinedSQLType sqlType = null;

            if (sqlTypeUUID != null ){
                sqlType = session.findSQLTypeByUUID(sqlTypeUUID);
            }
            col.getUserDefinedSQLType().setUpstreamType(sqlType);

            LoadSQLObjectAttributes(col, attributes);

            return col;
        }
    }

    /**
     * Creates a SQLException instance and adds it to the
     * objectIdMap. This ExceptionFactory is still used for loading older
     * files.
     */
    private class SQLExceptionFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) {
            return new Exception(attributes.getValue("message"));
        }
    }

    /**
     * Creates a SQLRelationship instance and adds it to the
     * objectIdMap.  Also dereferences the fk-table-ref and
     * pk-table-ref attributes if present.
     */
    private class SQLRelationshipFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) {
            SQLRelationship rel = new SQLRelationship();

            String id = attributes.getValue("id");
            if (id != null) {
                sqlObjectLoadIdMap.put(id, rel);
            } else {
                logger.warn("No ID found in relationship element while loading project!");
            }

            String fkTableId = attributes.getValue("fk-table-ref");
            String pkTableId = attributes.getValue("pk-table-ref");

            if (fkTableId != null && pkTableId != null) {
                SQLTable fkTable = (SQLTable) sqlObjectLoadIdMap.get(fkTableId);
                SQLTable pkTable = (SQLTable) sqlObjectLoadIdMap.get(pkTableId);
                try {
                    rel.attachRelationship(pkTable, fkTable, false);
                } catch (SQLObjectException e) {
                    logger.error("Couldn't attach relationship to pktable \""+pkTable.getName()+"\" and fktable \""+fkTable.getName()+"\"", e);
                    JOptionPane.showMessageDialog(null, "Failed to attach relationship to pktable \""+pkTable.getName()+"\" and fktable \""+fkTable.getName()+"\":\n"+e.getMessage());
                }
            } else {
                JOptionPane.showMessageDialog(null, "Missing pktable or fktable references for relationship id \""+id+"\"");
            }

            LoadSQLObjectAttributes(rel, attributes);

            return rel;
        }
    }

    /**
     * Creates a ColumnMapping instance and adds it to the
     * objectIdMap.  Also dereferences the fk-column-ref and
     * pk-column-ref attributes if present.
     */
    private class ColumnMappingFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) {
            SQLRelationship.ColumnMapping cmap = new SQLRelationship.ColumnMapping();

            String id = attributes.getValue("id");
            if (id != null) {
                sqlObjectLoadIdMap.put(id, cmap);
            } else {
                logger.warn("No ID found in column-mapping element while loading project!");
            }

            String fkColumnId = attributes.getValue("fk-column-ref");
            if (fkColumnId != null) {
                cmap.setFkColumn((SQLColumn) sqlObjectLoadIdMap.get(fkColumnId));
            }

            String pkColumnId = attributes.getValue("pk-column-ref");
            if (pkColumnId != null) {
                cmap.setPkColumn((SQLColumn) sqlObjectLoadIdMap.get(pkColumnId));
            }
            
            String fkTableId = attributes.getValue("fk-table");
            if (fkTableId != null) {
                cmap.setFkTable((SQLTable) sqlObjectLoadIdMap.get(fkTableId));
            }

            String fkColName = attributes.getValue("fk-col-name");
            if (fkColName != null) {
                cmap.setFkColName(fkColName);
            }
            
            return cmap;
        }
    }

    /**
     * Creates a SQLIndex instance and adds it to the objectIdMap.
     */
    private class SQLIndexFactory extends AbstractObjectCreationFactory {

        public Object createObject(Attributes attributes) {
            SQLIndex index = new SQLIndex();
            logger.debug("Loading index: "+attributes.getValue("name"));

            String pkIndex = attributes.getValue("primaryKeyIndex");
            if (Boolean.valueOf(pkIndex)) {
                index = currentTable.getPrimaryKeyIndex();
            }

            String id = attributes.getValue("id");
            if (id != null) {
                sqlObjectLoadIdMap.put(id, index);
            } else {
                logger.warn("No ID found in index element while loading project!");
            }
            for (int i = 0; i < attributes.getLength(); i++) {
                logger.debug("Attribute: \"" + attributes.getQName(i) + "\" Value:"+attributes.getValue(i));
            }
            index.setType(attributes.getValue("index-type"));

            LoadSQLObjectAttributes(index, attributes);

            return index;
        }
    }

    /**
     * Creates a SQLIndex instance and adds it to the
     * objectIdMap.  Also dereferences the column-ref if present.
     */
    private class SQLIndexColumnFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) {
            Column col = new Column();

            String id = attributes.getValue("id");
            if (id != null) {
                sqlObjectLoadIdMap.put(id, col);
            } else {
                logger.warn("No ID found in index-column element while loading project!");
            }

            String referencedColId = attributes.getValue("column-ref");
            if (referencedColId != null) {
                SQLColumn column = (SQLColumn) sqlObjectLoadIdMap.get(referencedColId);
                col.setColumn(column);
            }
            for (int i = 0; i < attributes.getLength(); i++) {
                logger.debug("Attribute: \"" + attributes.getQName(i) + "\" Value:"+attributes.getValue(i));
            }

            if (attributes.getValue("ascendingOrDescending") != null) {
                col.setAscendingOrDescending(SQLIndex.AscendDescend.valueOf(attributes.getValue("ascendingOrDescending")));
            }

            LoadSQLObjectAttributes(col, attributes);

            return col;
        }
    }

    /**
     * Creates a LiquibaseSettings instance and adds it to the objectIdMap.
     */
    private class LiquibaseSettingsFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) {
			return session.getLiquibaseSettings();
        }
    }

    private class DDLGeneratorFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) throws SQLException {
            try {
                GenericDDLGenerator ddlg =
                    (GenericDDLGenerator) Class.forName(attributes.getValue("type"), true, ProjectLoader.class.getClassLoader()).newInstance();
                ddlg.setTargetCatalog(attributes.getValue("target-catalog"));
                ddlg.setTargetSchema(attributes.getValue("target-schema"));
                return ddlg;
            } catch (Exception e) {
                logger.debug("Couldn't create DDL Generator instance. Returning generic instance.", e);
                return new GenericDDLGenerator();
            }
        }
    }

    private class FileFactory extends AbstractObjectCreationFactory {
        public Object createObject(Attributes attributes) {
            return new File(attributes.getValue("path"));
        }
    }

    /**
     * Just returns the existing profile manager (this way, all the profile results
     * will get added to the existing one)
     */
    private class ProfileManagerFactory extends AbstractObjectCreationFactory {
        @Override
        public Object createObject(Attributes attributes) throws SQLObjectException {
            return session.getProfileManager();
        }
    }

    /**
     * This class is used for backwards compatibility with 0.9.16 and older
     */
    private class ProfileResultFactory extends AbstractObjectCreationFactory {

        /**
         * The most recent table result encountered.
         */
        TableProfileResult tableProfileResult;

        @Override
        public Object createObject(Attributes attributes) throws SQLObjectException, ClassNotFoundException, InstantiationException, IllegalAccessException {
            String refid = attributes.getValue("ref-id");
            String className = attributes.getValue("type");

            if (refid == null) {
                throw new SQLObjectException("Missing mandatory attribute \"ref-id\" in <profile-result> element");
            }

            if (className == null) {
                throw new SQLObjectException("Missing mandatory attribute \"type\" in <profile-result> element");
            } else if (className.equals(TableProfileResult.class.getName())) {
                SQLTable t = (SQLTable) sqlObjectLoadIdMap.get(refid);

                // XXX we should actually store the settings together with each profile result, not rehash the current defaults
                tableProfileResult = new TableProfileResult(t, session.getProfileManager().getDefaultProfileSettings());

                return tableProfileResult;
            } else if (className.equals(ColumnProfileResult.class.getName())) {
                SQLColumn c = (SQLColumn) sqlObjectLoadIdMap.get(refid);
                if (tableProfileResult == null) {
                    throw new IllegalArgumentException("Column result does not have a parent");
                }
                ColumnProfileResult cpr = new ColumnProfileResult(c);
                tableProfileResult.addColumnProfileResult(cpr);
                return cpr;
            } else {
                throw new SQLObjectException("Profile result type \""+className+"\" not recognised");
            }
        }
    }
    
    private class TableProfileResultFactory extends AbstractObjectCreationFactory {
        
        public Object createObject(Attributes attributes) throws SQLObjectException {
            String refid = attributes.getValue("ref-id");
            
            if (refid == null) {
                throw new SQLObjectException("Missing mandatory attribute \"ref-id\" in <table-profile-result> element");
            }
            
            SQLTable t = (SQLTable) sqlObjectLoadIdMap.get(refid);
            
            return new TableProfileResult(t, session.getProfileManager().getDefaultProfileSettings());
        }
    }
    
    private class ColumnProfileResultFactory extends AbstractObjectCreationFactory {
        
        public Object createObject(Attributes attributes) throws SQLObjectException {
            String refid = attributes.getValue("ref-id");
            
            if (refid == null) {
                throw new SQLObjectException("Missing mandatory attribute \"ref-id\" id <column-profile-result> element");
            }
            
            SQLColumn c = (SQLColumn) sqlObjectLoadIdMap.get(refid);
            
            return new ColumnProfileResult(c);
        }
    }
 
    private class ProfileResultValueFactory extends AbstractObjectCreationFactory {
        @Override
        public Object createObject(Attributes attributes) throws SQLObjectException, ClassNotFoundException, InstantiationException, IllegalAccessException {
            String className = attributes.getValue("type");
            if (className == null) {
                throw new SQLObjectException("Missing mandatory attribute \"type\" in <avgValue> or <minValue> or <maxValue> element");
            } else if (className.equals(BigDecimal.class.getName()) ) {
                return new BigDecimal(attributes.getValue("value"));
            } else if (className.equals(Timestamp.class.getName()) ) {
                return new Timestamp( Timestamp.valueOf(attributes.getValue("value")).getTime() );
            } else {
                return new String(attributes.getValue("value"));
            }
        }
    }

    private class ProfileResultTopNValueFactory extends AbstractObjectCreationFactory {
        @Override
        public Object createObject(Attributes attributes) throws SQLObjectException, ClassNotFoundException, InstantiationException, IllegalAccessException {
            String className = attributes.getValue("type");
            int count = Integer.valueOf(attributes.getValue("count"));

            String per = attributes.getValue("percent");
            double percent = -1;
            if (per != null) {
                percent = Double.valueOf(per);
            }

            String value = attributes.getValue("value");
            
            String otherValuesString = attributes.getValue("otherValues");
            if (otherValuesString == null) {
                otherValuesString = "false";
            }
            Boolean otherValues = Boolean.parseBoolean(otherValuesString);

            if (className == null || className.length() == 0 ) {
                return new ColumnValueCount(null,count, percent, otherValues);
            } else if (className.equals(BigDecimal.class.getName()) ) {
                return new ColumnValueCount(new BigDecimal(value),count, percent, otherValues);
            } else if (className.equals(Timestamp.class.getName()) ) {
                return new ColumnValueCount(new Timestamp( Timestamp.valueOf(value).getTime() ),count, percent, otherValues);
            } else if (className.equals(String.class.getName()) ) {
                return new ColumnValueCount(value,count, percent, otherValues);
            } else {
                return new ColumnValueCount(value,count, percent, otherValues);
            }
        }
    }

    /**
     * See {@link #modified}.
     */
    public boolean isModified() {
        return modified;
    }

    /**
     * See {@link #modified}.
     */
    public void setModified(boolean modified) {
        if (logger.isDebugEnabled()) logger.debug("Project modified: "+modified);
        this.modified = modified;
    }

    protected ArchitectSession getSession() {
        return session;
    }

    /**
     * Returns the file that this project was most recently
     * saved to or loaded from.
     */
    public File getFile()  {
        return this.file;
    }

    /**
     * Tells this project which file it was most recently
     * saved to or loaded from.
     */
    public void setFile(File argFile) {
        this.file = argFile;
    }

    /**
     * Clears the file version if the file to save to is being changed to a
     * new location.
     */
    public void clearFileVersion() {
        fileVersion = null;
    }

    /**
     * Adds all the tables in the given database into the playpen database.  This is really only
     * for loading projects, so please think twice about using it for other stuff.
     *
     * @param db The database to add tables from.  The database must contain tables directly.
     * @throws SQLObjectException If adding the tables of db fails
     */
    public void addAllTablesFrom(SQLDatabase db) throws SQLObjectException {
        SQLDatabase ppdb = getSession().getTargetDatabase();
        for (SQLTable table : db.getChildren(SQLTable.class)) {
            ppdb.addChild(table);
        }
    }
}
