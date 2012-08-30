package com.thinkaurelius.titan.diskstorage.berkeleydb.je;


import com.google.common.base.Preconditions;
import com.sleepycat.je.*;
import com.thinkaurelius.titan.core.GraphStorageException;
import com.thinkaurelius.titan.diskstorage.util.*;

import static com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration.*;

import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import com.thinkaurelius.titan.graphdb.database.idassigner.DefaultIDBlockSizer;
import com.thinkaurelius.titan.graphdb.database.idassigner.IDBlockSizer;
import com.thinkaurelius.titan.util.system.IOUtils;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class BerkeleyJEStorageManager implements KeyValueStorageManager {

    private final Logger log = LoggerFactory.getLogger(BerkeleyJEStorageManager.class);


    public static final String CACHE_KEY = "cache_percentage";
    public static final int CACHE_DEFAULT = 65;
    
    private static final String IDMANAGER_KEY = "idmanager_table";
    private static final String IDMANAGER_DEFAULT = "titan_idmanager";
    
	private final Map<String,BerkeleyJEKeyValueStore> stores;
	

	private Environment environment;
	private final File directory;
	private final boolean transactional;
	private final boolean isReadOnly;
	private final boolean batchLoading;

    private IDBlockSizer blockSizer;
    private volatile boolean hasActiveIDAcquisition;
    private final String idManagerTableName;
    private final ReentrantLock idAcquisitionLock = new ReentrantLock();

	public BerkeleyJEStorageManager(Configuration configuration) {
		stores = new HashMap<String, BerkeleyJEKeyValueStore>();
		directory=new File(configuration.getString(STORAGE_DIRECTORY_KEY));
        Preconditions.checkArgument(directory.isDirectory() && directory.canWrite(),"Cannot open or write to directory: " + directory);
		isReadOnly= configuration.getBoolean(STORAGE_READONLY_KEY,STORAGE_READONLY_DEFAULT);
		batchLoading=configuration.getBoolean(STORAGE_BATCH_KEY,STORAGE_BATCH_DEFAULT);
        boolean transactional = configuration.getBoolean(STORAGE_TRANSACTIONAL_KEY,STORAGE_TRANSACTIONAL_DEFAULT);
        this.blockSizer = new DefaultIDBlockSizer(configuration.getLong(IDAUTHORITY_BLOCK_SIZE_KEY,IDAUTHORITY_BLOCK_SIZE_DEFAULT));
        this.hasActiveIDAcquisition=false;
        if (batchLoading) {
            if (transactional) log.warn("Disabling transactional since batch loading is enabled!");
            transactional=false;
        }
        this.transactional=transactional;
        int cachePercentage = configuration.getInt(CACHE_KEY,CACHE_DEFAULT);
        
        idManagerTableName = configuration.getString(IDMANAGER_KEY,IDMANAGER_DEFAULT);
        initialize(cachePercentage);
	}

	private void initialize(int cachePercent) throws GraphStorageException {
		try {
			EnvironmentConfig envConfig = new EnvironmentConfig();
			envConfig.setAllowCreate(true);
			envConfig.setTransactional(transactional);
			envConfig.setCachePercent(cachePercent);
			
			if (batchLoading) {
				envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CHECKPOINTER, "false");
				envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER, "false");
			}

			
			//Open the environment
			environment = new Environment(directory, envConfig);
		} catch (DatabaseException e) {
			throw new GraphStorageException("Error during BerkeleyJE initialization: ",e);
		}
		
	}
	
	@Override
	public BDBTxHandle beginTransaction() {
		try {
			Transaction tx = null;
			if (transactional) {
				tx = environment.beginTransaction(null, null);
			}
			return new BDBTxHandle(tx);
		} catch (DatabaseException e) {
			throw new GraphStorageException("Could not start BerkeleyJE transaction",e);
		}
	}


	@Override
	public BerkeleyJEKeyValueStore openDatabase(String name) throws GraphStorageException {
		Preconditions.checkNotNull(name);
        if (stores.containsKey(name)) {
			BerkeleyJEKeyValueStore store = stores.get(name);
			return store;
		}
		try {
			DatabaseConfig dbConfig = new DatabaseConfig();
			dbConfig.setReadOnly(isReadOnly);
			dbConfig.setAllowCreate(true);
			dbConfig.setTransactional(transactional);

			dbConfig.setKeyPrefixing(true);
			
			if (batchLoading) {
				dbConfig.setDeferredWrite(true);
			}
			
			Database db = environment.openDatabase(null, name, dbConfig);
			BerkeleyJEKeyValueStore store =  new BerkeleyJEKeyValueStore(name,db,this);
			stores.put(name, store);
			return store;
		} catch (DatabaseException e) {
			throw new GraphStorageException("Could not open BerkeleyJE data store",e);
		}
	}


    @Override
    public void setIDBlockSizer(IDBlockSizer sizer) {
        idAcquisitionLock.lock();
        try {
            if (hasActiveIDAcquisition) throw new IllegalStateException("IDBlockSizer cannot be changed after IDs have already been assigned");
            this.blockSizer=sizer;
        } finally {
            idAcquisitionLock.unlock();
        }
    }

    @Override
    public long[] getIDBlock(int partition) {
        hasActiveIDAcquisition=true;
        BerkeleyJEKeyValueStore idDB = openDatabase(idManagerTableName);
        ByteBuffer key = ByteBufferUtil.getIntByteBuffer(partition);
        idAcquisitionLock.lock();
        try {
            long blockSize = blockSizer.getBlockSize(partition);
            Preconditions.checkArgument(blockSize<Integer.MAX_VALUE);
            BDBTxHandle tx = beginTransaction();
            ByteBuffer value = idDB.get(key,tx);
            int counter = 1;
            if (value!=null) {
                assert value.remaining()==4;
                counter = value.getInt();
            }
            Preconditions.checkArgument(Integer.MAX_VALUE-blockSize>counter);
            int next = counter + (int)blockSize;
            idDB.insert(new KeyValueEntry(key,ByteBufferUtil.getIntByteBuffer(next)),tx.getTransaction(),true);
            tx.commit();
            return new long[]{counter,next};
        } finally {
            idAcquisitionLock.unlock();
        }

    }


    void removeDatabase(BerkeleyJEKeyValueStore db) {
		if (!stores.containsKey(db.getName())) {
			throw new GraphStorageException("Tried to remove an unkown database from the storage manager");
		}
		stores.remove(db.getName());
	}


	@Override
	public void close() throws GraphStorageException {
		if (environment!=null) {
            BerkeleyJEKeyValueStore idmanager = stores.get(idManagerTableName);
            if (idmanager!=null) idmanager.close();
			if (!stores.isEmpty()) throw new GraphStorageException("Cannot shutdown manager since some databases are still open");
			try {
				environment.close();
			} catch (DatabaseException e) {
				throw new GraphStorageException("Could not close BerkeleyJE database",e);
			}
		}
		
	}

    @Override
    public void clearStorage() {
        if (!stores.isEmpty()) throw new IllegalStateException("Cannot delete store, since database is open: " + stores.keySet().toString());

        Transaction tx = null;
        for (String db : environment.getDatabaseNames()) {
            environment.removeDatabase(tx,db);
        }
        close();
        IOUtils.deleteFromDirectory(directory);
    }


}
