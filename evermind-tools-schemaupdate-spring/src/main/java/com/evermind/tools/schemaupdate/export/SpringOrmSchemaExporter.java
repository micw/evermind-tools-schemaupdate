package com.evermind.tools.schemaupdate.export;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Set;

import javax.sql.DataSource;
import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import com.evermind.tools.schemaupdate.liquibase.LiqibaseHelper;
import com.evermind.tools.schemaupdate.liquibase.TablenameFilter;

import liquibase.database.Database;
import liquibase.diff.DiffResult;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.DiffToChangeLog;
import liquibase.exception.LiquibaseException;
import liquibase.serializer.core.xml.XMLChangeLogSerializer;
import liquibase.snapshot.DatabaseSnapshot;

/**
 * http://stackoverflow.com/questions/34612019/programmatic-schemaexport-schemaupdate-with-hibernate-5-and-spring-4
 * http://hillert.blogspot.de/2010/05/using-hibernates-schemaexport-feature.html
 * http://stackoverflow.com/questions/32780664/hibernate-migration-from-4-3-x-to-5-x-for-method-org-hibernate-cfg-configuration
 * http://blog.essential-bytes.de/flyway-hibernate-und-jpa-integrieren/
 * 
 * @author mwyraz
 */
public class SpringOrmSchemaExporter
{
    protected final Logger LOG=LoggerFactory.getLogger(getClass()); 

    protected File schemaUpdateOutputFile;
    protected Set<String> ignoredTables=new HashSet<>();
    
    public void exportSchemaAndUpdates(Database referenceDatabase, Database actualDatabase,String optionalSchemaName) throws SQLException, LiquibaseException, IOException, ParserConfigurationException
    {
        if (optionalSchemaName!=null) actualDatabase.setDefaultSchemaName(optionalSchemaName);
        exportSchemaAndUpdates(LiqibaseHelper.createDatabaseSnapshot(referenceDatabase),LiqibaseHelper.createDatabaseSnapshot(actualDatabase));
    }
    
    public void exportSchemaAndUpdates(Database referenceDatabase, Connection actualDatabaseConnection,String optionalSchemaName) throws SQLException, LiquibaseException, IOException, ParserConfigurationException
    {
        exportSchemaAndUpdates(referenceDatabase,LiqibaseHelper.getLiquibaseDatabase(actualDatabaseConnection),optionalSchemaName);
    }
    
    public void exportSchemaAndUpdates(Database referenceDatabase, DataSource actualDatabase, String optionalSchemaName) throws SQLException, LiquibaseException, IOException, ParserConfigurationException
    {
        try (Connection actualDatabaseConnection=actualDatabase.getConnection())
        {
            exportSchemaAndUpdates(referenceDatabase,actualDatabaseConnection,optionalSchemaName);
        }
    }
    
    public void exportSchemaAndUpdates(DatabaseSnapshot referenceState, DatabaseSnapshot actualDatabase) throws SQLException, LiquibaseException, IOException, ParserConfigurationException
    {
        DiffResult diff=LiqibaseHelper.createDiff(referenceState, actualDatabase);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos);
        
        DiffOutputControl diffOutputControl=new DiffOutputControl(false,false,false);
        if (!ignoredTables.isEmpty())
        {
            diffOutputControl.setObjectChangeFilter(new TablenameFilter(ignoredTables));
        }
        DiffToChangeLog diffToChangeLog = new DiffToChangeLog(diff, diffOutputControl);
        diffToChangeLog.setIdRoot(new SimpleDateFormat("yyyyMMdd-01").format(System.currentTimeMillis()));
        
        diffToChangeLog.print(out, new XMLChangeLogSerializer());
        String schemaUpdates=baos.toString("utf-8");
        
        // Prüfung muss nach Export erfolgen, da die Nummerierung im Export sonst nicht stimmt
        if (diffToChangeLog.generateChangeSets().isEmpty())
        {
            LOG.debug("No outstanding schemaupdates found.");
            return;
        }
        
        if (schemaUpdateOutputFile==null)
        {
            LOG.warn("Outstanding schemaupdates:\n{}",schemaUpdates);
        }
    }

    public void exportSchemaAndUpdates(LocalContainerEntityManagerFactoryBean entityManagerFactoryBean, String optionalSchemaName) throws SQLException, LiquibaseException, IOException, ParserConfigurationException
    {
        exportSchemaAndUpdates(entityManagerFactoryBean,null, optionalSchemaName);
    }
    
    
    public void exportSchemaAndUpdates(LocalContainerEntityManagerFactoryBean entityManagerFactoryBean, Connection connectionToUse, String optionalSchemaName) throws SQLException, LiquibaseException, IOException, ParserConfigurationException
    {
        try
        {
            LOG.debug("Creating schema from hibernate mapping");
            
            String className=getClass().getPackage().getName()+".HibernateAdapter";
            IHibernateAdapter hibernateAdapter;
            try
            {
                hibernateAdapter=(IHibernateAdapter)Class.forName(className).newInstance();
            }
            catch (Exception ex)
            {
                throw new LiquibaseException("Unable to load "+className+" - is an implementation in the classpath?",ex);
            }
            
            Database referenceDatabase=hibernateAdapter.getHibernateDatabaseFromSpring(entityManagerFactoryBean);
            
            if (connectionToUse==null)
            {
                exportSchemaAndUpdates(referenceDatabase,entityManagerFactoryBean.getDataSource(), optionalSchemaName);
            }
            else
            {
                exportSchemaAndUpdates(referenceDatabase,connectionToUse, optionalSchemaName);
            }
        }
        catch (Exception ex)
        {
            LOG.warn("Failed to check schema",ex);
        }
    }
 
    public void addIgnoredTables(String...tables)
    {
        for (String table: tables)
        {
            ignoredTables.add(table);
        }
    }

}
