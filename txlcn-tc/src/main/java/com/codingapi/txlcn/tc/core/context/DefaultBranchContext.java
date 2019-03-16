/*
 * Copyright 2017-2019 CodingApi .
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codingapi.txlcn.tc.core.context;

import com.codingapi.txlcn.common.exception.BranchContextException;
import com.codingapi.txlcn.common.util.AopTargetUtils;
import com.codingapi.txlcn.common.util.function.Supplier;
import com.codingapi.txlcn.tc.aspect.InvocationInfo;
import com.codingapi.txlcn.tc.config.TxClientConfig;
import com.codingapi.txlcn.tc.core.mode.lcn.LcnConnectionProxy;
import com.codingapi.txlcn.tc.core.mode.txc.analy.def.PrimaryKeysProvider;
import com.codingapi.txlcn.tc.core.mode.txc.analy.def.bean.TableStruct;
import com.codingapi.txlcn.tracing.TracingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Description:
 * Date: 19-1-22 下午6:17
 *
 * @author ujued
 * @see AttachmentCache
 * @see PrimaryKeysProvider
 */
@Component
@Slf4j
public class DefaultBranchContext implements BranchContext {

    private final AttachmentCache attachmentCache;

    private final List<PrimaryKeysProvider> primaryKeysProviders;

    private Map<Integer, DataSource> modDataSourceMap = new HashMap<>();

    private final TxClientConfig clientConfig;

    @Autowired
    public DefaultBranchContext(AttachmentCache attachmentCache, TxClientConfig clientConfig,
                                @Autowired(required = false) List<PrimaryKeysProvider> primaryKeysProviders,
                                @Autowired(required = false) List<TransactionModeProperties> transactionModePropertiesList) {
        this.attachmentCache = attachmentCache;
        this.primaryKeysProviders = primaryKeysProviders;
        this.clientConfig = clientConfig;
        cacheTxModeProperties(transactionModePropertiesList);
    }

    private void cacheTxModeProperties(List<TransactionModeProperties> propertiesList) {
        if (Objects.nonNull(propertiesList)) {
            propertiesList.forEach(properties -> {
                Properties props = properties.provide();
                if (Objects.nonNull(props)) {
                    props.forEach(DefaultBranchContext.this::cacheIfAbsentProperty);
                }
            });
        }
    }

    @Override
    public LcnConnectionProxy lcnConnection(String groupId, DataSource dataSource, Supplier<LcnConnectionProxy, SQLException> supplier) throws SQLException {
        if (attachmentCache.containsKey(groupId, dataSource)) {
            return attachmentCache.attachment(groupId, dataSource);
        }
        LcnConnectionProxy lcnConnectionProxy = supplier.get();
        attachmentCache.attach(groupId, dataSource, lcnConnectionProxy);
        return lcnConnectionProxy;
    }

    @Override
    public Collection<Object> lcnConnections(String groupId) throws BranchContextException {
        return attachmentCache.attachments(groupId).entrySet()
                .stream()
                .filter(entry -> entry.getKey() instanceof DataSource)
                .map(Map.Entry::getValue)
                .collect(Collectors.toSet());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void prepareTxcLocks(String groupId, String unitId, Set<String> lockIdList) {
        String lockKey = unitId + ".txc.lock";
        if (attachmentCache.containsKey(groupId, lockKey)) {
            ((Set) attachmentCache.attachment(groupId, lockKey)).addAll(lockIdList);
            return;
        }
        Set<String> lockList = new HashSet<>(lockIdList);
        attachmentCache.attach(groupId, lockKey, lockList);
    }

    @Override
    public Set<String> txcLockSet(String groupId, String unitId) throws BranchContextException {
        String lockKey = unitId + ".txc.lock";
        if (attachmentCache.containsKey(groupId, lockKey)) {
            return attachmentCache.attachment(groupId, lockKey);
        }
        throw new BranchContextException("non exists lock id.");
    }

    @Override
    public TableStruct tableStruct(String table, Supplier<TableStruct, SQLException> structSupplier) throws SQLException {
        String tableStructKey = table + ".struct";
        if (attachmentCache.containsKey(tableStructKey)) {
            log.debug("cache hit! table {}'s struct.", table);
            return attachmentCache.attachment(tableStructKey);
        }
        TableStruct tableStruct = structSupplier.get();
        if (Objects.nonNull(primaryKeysProviders)) {
            primaryKeysProviders.forEach(primaryKeysProvider -> {
                List<String> users = primaryKeysProvider.provide().get(table);
                if (Objects.nonNull(users)) {
                    List<String> primaryKes = tableStruct.getPrimaryKeys();
                    primaryKes.addAll(users.stream()
                            .filter(key -> !primaryKes.contains(key))
                            .filter(key -> tableStruct.getColumns().keySet().contains(key)).collect(Collectors.toList()));
                    tableStruct.setPrimaryKeys(primaryKes);
                }
            });
        }
        attachmentCache.attach(tableStructKey, tableStruct);
        return tableStruct;
    }

    @Override
    public void startTx(boolean isOriginalBranch, String groupId) {
        TxContext txContext = new TxContext();
        // 事务发起方判断
        txContext.setOriginalBranch(isOriginalBranch);
        txContext.setGroupId(TracingContext.tracing().groupId());
        String txContextKey = txContext.getGroupId() + ".dtx";
        attachmentCache.attach(txContextKey, txContext);
        log.debug("Start TxContext[{}]", txContext.getGroupId());
    }

    /**
     * 在用户业务前生成，业务后销毁
     *
     * @param groupId groupId
     */
    @Override
    public void destroyTx(String groupId) {
        attachmentCache.remove(groupId + ".dtx");
        log.debug("Destroy TxContext[{}]", groupId);
    }

    @Override
    public TxContext txContext(String groupId) {
        return attachmentCache.attachment(groupId + ".dtx");
    }

    @Override
    public TxContext txContext() {
        return txContext(TracingContext.tracing().groupId());
    }

    @Override
    public void destroyTx() {
        if (!hasTxContext()) {
            throw new IllegalStateException("non TxContext.");
        }
        destroyTx(txContext().getGroupId());
    }

    @Override
    public boolean hasTxContext() {
        return TracingContext.tracing().hasGroup() && txContext(TracingContext.tracing().groupId()) != null;
    }

    @Override
    public boolean isTransactionTimeout() {
        if (!hasTxContext()) {
            throw new IllegalStateException("non TxContext.");
        }
        return (System.currentTimeMillis() - txContext().getCreateTime()) >= clientConfig.getDtxTime();
    }

    @Override
    public int transactionState(String groupId) {
        return this.attachmentCache.containsKey(groupId, "rollback-only") ? 0 : 1;
    }

    @Override
    public void setRollbackOnly(String groupId) {
        this.attachmentCache.attach(groupId, "rollback-only", true);
    }

    @Override
    public boolean isProxyConnection(String transactionType) {
        return this.attachmentCache.containsKey(transactionType + ".connection.proxy")
                && this.attachmentCache.attachment(transactionType + ".connection.proxy").equals("true");
    }

    @Override
    public void cacheIfAbsentProperty(Object key, Object value) {
        if (!this.attachmentCache.containsKey(key)) {
            this.attachmentCache.attach(key, value);
        }
    }

    @Override
    public DataSource getByConnection(String groupId, Connection connection) {
        return this.attachmentCache.attachment(groupId, connection);
    }

    @Override
    public void linkDataSource(String groupId, Connection connection, DataSource dataSource) {
        this.attachmentCache.attach(groupId, connection, dataSource);
    }

    @Override
    public void mapDataSources(List<DataSource> dataSources) {
        if (Objects.isNull(dataSources)) {
            log.warn("mod non exists javax.sql.DataSource");
        }
        dataSources.forEach(dataSource -> {
            try {
                DataSource ds = (DataSource) AopTargetUtils.getTarget(dataSource);
                this.modDataSourceMap.put(ds.hashCode(), ds);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public DataSource dataSource(Integer dataSource) {
        return this.modDataSourceMap.get(dataSource);
    }

    /**
     * 清理事务时调用
     *
     * @param groupId groupId
     */
    @Override
    public void clearGroup(String groupId) {
        // 事务组相关的数据
        this.attachmentCache.removeAll(groupId);
    }

    @Override
    public void cacheTransactionAttribute(String mappedMethodName, Map<Object, Object> attributes) {
        NonSpringRuntimeContext.instance().cacheTransactionAttribute(mappedMethodName, attributes);
    }

    @Override
    public TransactionAttribute getTransactionAttribute(String unitId) {
        return NonSpringRuntimeContext.instance().getTransactionAttribute(unitId);
    }

    @Override
    public TransactionAttribute getTransactionAttribute(InvocationInfo invocationInfo) {
        return NonSpringRuntimeContext.instance().getTransactionAttribute(invocationInfo);
    }

    @Override
    public void updateTransactionAttribute(String mappedMethodName, Map<Object, Object> attributes) {
        NonSpringRuntimeContext.instance().updateTransactionAttribute(mappedMethodName, attributes);
    }
}
