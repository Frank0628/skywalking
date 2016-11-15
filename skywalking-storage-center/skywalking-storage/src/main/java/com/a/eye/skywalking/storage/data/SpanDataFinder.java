package com.a.eye.skywalking.storage.data;

import com.a.eye.skywalking.logging.api.ILog;
import com.a.eye.skywalking.logging.api.LogManager;
import com.a.eye.skywalking.storage.block.index.BlockIndexEngine;
import com.a.eye.skywalking.storage.config.Config;
import com.a.eye.skywalking.storage.config.Constants;
import com.a.eye.skywalking.storage.data.exception.ConnectionNotFoundException;
import com.a.eye.skywalking.storage.data.file.DataFileReader;
import com.a.eye.skywalking.storage.data.index.*;
import com.a.eye.skywalking.storage.data.spandata.SpanData;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static com.a.eye.skywalking.storage.config.Constants.SQL.DEFAULT_PASSWORD;
import static com.a.eye.skywalking.storage.config.Constants.SQL.DEFAULT_USER;
import static com.a.eye.skywalking.storage.util.PathResolver.getAbsolutePath;

public class SpanDataFinder {
    private static ILog                 logger               = LogManager.getLogger(SpanDataFinder.class);
    private static IndexDataSourceCache datasourceCache      = new IndexDataSourceCache(Config.Finder.CACHED_SIZE);
    private static ReentrantLock        createDatasourceLock = new ReentrantLock();

    public static List<SpanData> find(String traceId) {
        long blockIndex = BlockIndexEngine.newFinder().find(fetchStartTimeFromTraceId(traceId));
        if (blockIndex == 0) {
            return new ArrayList<SpanData>();
        }

        IndexDBConnector indexDBConnector = null;
        IndexMetaCollection indexMetaCollection = null;
        try {
            indexDBConnector = fetchIndexDBConnector(blockIndex);
            indexMetaCollection = indexDBConnector.queryByTraceId(spiltTraceId(traceId));
        } finally {
            if (indexDBConnector != null) {
                indexDBConnector.close();
            }
        }

        if (indexMetaCollection == null) {
            return new ArrayList<SpanData>();
        }

        Iterator<IndexMetaGroup<String>> iterator = IndexMetaCollections.group(indexMetaCollection, new GroupKeyBuilder<String>() {
            @Override
            public String buildKey(IndexMetaInfo metaInfo) {
                return metaInfo.getFileName();
            }
        }).iterator();

        List<SpanData> result = new ArrayList<SpanData>();
        while (iterator.hasNext()) {
            IndexMetaGroup<String> group = iterator.next();
            DataFileReader reader = null;
            try {
                reader = new DataFileReader(group.getKey());
                result.addAll(reader.read(group.getMetaInfo()));
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }
        }

        return result;
    }

    private static long[] spiltTraceId(String traceId){
        return null;
    }

    private static IndexDBConnector fetchIndexDBConnector(long blockIndex) {
        HikariDataSource datasource = getOrCreate(blockIndex);
        IndexDBConnector indexDBConnector;
        try {
            indexDBConnector = new IndexDBConnector(datasource.getConnection());
        } catch (SQLException e) {
            throw new ConnectionNotFoundException("get connection failure.", e);
        }
        return indexDBConnector;
    }

    private static HikariDataSource getOrCreate(long blockIndex) {
        HikariDataSource datasource = datasourceCache.get(blockIndex);
        if (datasource == null) {
            createDatasourceLock.lock();
            try {
                if (datasource == null) {
                    HikariConfig dataSourceConfig = generateDatasourceConfig(blockIndex);
                    datasource = new HikariDataSource(dataSourceConfig);
                    datasourceCache.put(blockIndex, datasource);
                }
            } finally {
                createDatasourceLock.unlock();
            }
        }
        return datasource;
    }

    private static HikariConfig generateDatasourceConfig(long blockIndex) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(new ConnectURLGenerator(getAbsolutePath(Config.DataIndex.PATH), Config.DataIndex.FILE_NAME).generate(blockIndex));
        config.setDriverClassName(Constants.DRIVER_CLASS_NAME);
        config.setUsername(DEFAULT_USER);
        config.setPassword(DEFAULT_PASSWORD);
        config.setMaximumPoolSize(Config.Finder.DataSource.MAX_POOL_SIZE);
        config.setMinimumIdle(Config.Finder.DataSource.MIN_IDLE);
        return config;
    }

    private static long fetchStartTimeFromTraceId(String traceId) {
        String[] traceIdSegment = traceId.split("\\.");
        return Long.parseLong(traceIdSegment[traceIdSegment.length - 5]);
    }

    private static class IndexDataSourceCache extends LinkedHashMap<Long, HikariDataSource> {

        private int cacheSize;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, HikariDataSource> eldest) {
            boolean removed = size() > cacheSize;
            if (removed) {
                eldest.getValue().close();
            }
            return removed;
        }

        public IndexDataSourceCache(int cacheSize) {
            super((int) Math.ceil(cacheSize / 0.75) + 1, 0.75f, true);
            this.cacheSize = cacheSize;
        }
    }
}
