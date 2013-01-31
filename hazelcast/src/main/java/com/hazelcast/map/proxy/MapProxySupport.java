/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.proxy;

import com.hazelcast.core.EntryListener;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.Member;
import com.hazelcast.core.Transaction;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.instance.ThreadContext;
import com.hazelcast.map.*;
import com.hazelcast.monitor.LocalMapStats;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.query.Predicate;
import com.hazelcast.spi.*;
import com.hazelcast.transaction.TransactionImpl;
import com.hazelcast.util.QueryResultStream;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.hazelcast.map.MapService.SERVICE_NAME;

abstract class MapProxySupport extends AbstractDistributedObject {

    protected final String name;
    protected final MapService mapService;

    protected MapProxySupport(final String name, final MapService mapService, NodeEngine nodeEngine) {
        super(nodeEngine);
        this.name = name;
        this.mapService = mapService;
    }

    protected Data getInternal(Data key) {
        final boolean nearCacheEnabled = mapService.getMapContainer(name).isNearCacheEnabled();
        if (nearCacheEnabled) {
            Data cachedData = mapService.getFromNearCache(name, key);
            if (cachedData != null)
                return cachedData;
        }
        GetOperation operation = new GetOperation(name, key);
        return (Data) doTxnAwareOperation(key, operation);
    }

    private Object doTxnAwareOperation(Data key, AbstractMapOperation operation) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        operation.setTxnId(getCurrentTransactionId());
        operation.setThreadId(ThreadContext.getThreadId());
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            Future f = invocation.invoke();
            return f.get();
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected Future<Data> getAsyncInternal(final Data key) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        GetOperation operation = new GetOperation(name, key);
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            return nodeEngine.getAsyncInvocationService().invoke(invocation);
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected Data putInternal(final Data key, final Data value, final long ttl, final TimeUnit timeunit) {
        PutOperation operation = new PutOperation(name, key, value, null, getTimeInMillis(ttl, timeunit));
        return (Data) doTxnalOperation(key, operation);
    }

    protected boolean tryPutInternal(final Data key, final Data value, final long timeout, final TimeUnit timeunit) {
        TryPutOperation operation = new TryPutOperation(name, key, value, null, getTimeInMillis(timeout, timeunit));
        return (Boolean) doTxnalOperation(key, operation);
    }

    protected Data putIfAbsentInternal(final Data key, final Data value, final long ttl, final TimeUnit timeunit) {
        PutIfAbsentOperation operation = new PutIfAbsentOperation(name, key, value, null, getTimeInMillis(ttl, timeunit));
        return (Data) doTxnalOperation(key, operation);
    }

    protected void putTransientInternal(final Data key, final Data value, final long ttl, final TimeUnit timeunit) {
        PutTransientOperation operation = new PutTransientOperation(name, key, value, null, getTimeInMillis(ttl, timeunit));
        doTxnalOperation(key, operation);
    }

    private Object doTxnalOperation(Data key, AbstractMapOperation operation) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        operation.setTxnId(attachTxnParticipant(partitionId));
        operation.setThreadId(ThreadContext.getThreadId());
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            Future f = invocation.invoke();
            return f.get();
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected Future<Data> putAsyncInternal(final Data key, final Data value) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        String txnId = attachTxnParticipant(partitionId);
        PutOperation operation = new PutOperation(name, key, value, txnId, -1);
        operation.setThreadId(ThreadContext.getThreadId());
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            return nodeEngine.getAsyncInvocationService().invoke(invocation);
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected boolean replaceInternal(final Data key, final Data oldValue, final Data newValue) {
        ReplaceIfSameOperation operation = new ReplaceIfSameOperation(name, key, oldValue, newValue, null);
        return (Boolean) doTxnalOperation(key, operation);
    }

    protected Data replaceInternal(final Data key, final Data value) {
        ReplaceOperation operation = new ReplaceOperation(name, key, value, null);
        return (Data) doTxnalOperation(key, operation);
    }

    protected void setInternal(final Data key, final Data value, final long ttl, final TimeUnit timeunit) {
        SetOperation operation = new SetOperation(name, key, value, null, ttl);
        doTxnalOperation(key, operation);
    }

    protected boolean evictInternal(final Data key) {
        EvictOperation operation = new EvictOperation(name, key, null);
        return (Boolean) doTxnalOperation(key, operation);
    }

    protected Data removeInternal(Data key) {
        RemoveOperation operation = new RemoveOperation(name, key, null);
        return (Data) doTxnalOperation(key, operation);
    }

    protected boolean removeInternal(final Data key, final Data value) {
        RemoveIfSameOperation operation = new RemoveIfSameOperation(name, key, value, null);
        return (Boolean) doTxnalOperation(key, operation);
    }

    protected Data tryRemoveInternal(final Data key, final long timeout, final TimeUnit timeunit) throws TimeoutException {
        TryRemoveOperation operation = new TryRemoveOperation(name, key, null, getTimeInMillis(timeout, timeunit));
        return (Data) doTxnalOperation(key, operation);
    }

    protected Future<Data> removeAsyncInternal(final Data key) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        String txnId = attachTxnParticipant(partitionId);
        RemoveOperation operation = new RemoveOperation(name, key, txnId);
        operation.setThreadId(ThreadContext.getThreadId());
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            return nodeEngine.getAsyncInvocationService().invoke(invocation);
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected boolean containsKeyInternal(Data key) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        ContainsKeyOperation containsKeyOperation = new ContainsKeyOperation(name, key);
        containsKeyOperation.setServiceName(SERVICE_NAME);
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, containsKeyOperation,
                    partitionId).build();
            Future f = invocation.invoke();
            return (Boolean) nodeEngine.toObject(f.get());
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    public int size() {
        try {
            MapSizeOperation mapSizeOperation = new MapSizeOperation(name);
            Map<Integer, Object> results = nodeEngine.getOperationService()
                    .invokeOnAllPartitions(SERVICE_NAME, mapSizeOperation);
            int total = 0;
            for (Object result : results.values()) {
                Integer size = (Integer) nodeEngine.toObject(result);
                total += size;
            }
            return total;
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    public boolean containsValueInternal(Data dataValue) {
        try {
            ContainsValueOperation containsValueOperation = new ContainsValueOperation(name, dataValue);
            Map<Integer, Object> results = nodeEngine.getOperationService()
                    .invokeOnAllPartitions(SERVICE_NAME, containsValueOperation);
            for (Object result : results.values()) {
                Boolean contains = (Boolean) nodeEngine.toObject(result);
                if (contains)
                    return true;
            }
            return false;
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    public boolean isEmpty() {
        try {
            MapIsEmptyOperation mapIsEmptyOperation = new MapIsEmptyOperation(name);
            Map<Integer, Object> results = nodeEngine.getOperationService()
                    .invokeOnAllPartitions(SERVICE_NAME, mapIsEmptyOperation);
            for (Object result : results.values()) {
                if (!(Boolean) nodeEngine.toObject(result))
                    return false;
            }
            return true;
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    // todo optimize this
    protected Map<Data, Data> getAllDataInternal(final Set<Data> keys) {
        Map<Data, Data> res = new HashMap(keys.size());
        for (Data key : keys) {
            res.put(key, getInternal(key));
        }
        return res;
    }

    // todo optimize this
    protected Map<Object, Object> getAllObjectInternal(final Set<Data> keys) {
        Map<Object, Object> res = new HashMap(keys.size());
        for (Data key : keys) {
            res.put(nodeEngine.toObject(key), nodeEngine.toObject(getInternal(key)));
        }
        return res;
    }

    // todo optimize these
    protected void putAllDataInternal(final Map<? extends Data, ? extends Data> m) {
        for (Entry<? extends Data, ? extends Data> entry : m.entrySet()) {
            putInternal(entry.getKey(), entry.getValue(), -1, null);
        }
    }

    // todo optimize these
    protected void putAllObjectInternal(final Map<? extends Object, ? extends Object> m) {
        for (Entry<? extends Object, ? extends Object> entry : m.entrySet()) {
            putInternal(nodeEngine.toData(entry.getKey()), nodeEngine.toData(entry.getValue()), -1, null);
        }
    }

    protected void lockInternal(final Data key) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        LockOperation operation = new LockOperation(name, key);
        operation.setThreadId(ThreadContext.getThreadId());
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            Future future = invocation.invoke();
            future.get();
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected void unlockInternal(final Data key) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        UnlockOperation operation = new UnlockOperation(name, key);
        operation.setThreadId(ThreadContext.getThreadId());
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            Future future = invocation.invoke();
            future.get();
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected boolean isLockedInternal(final Data key) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        IsLockedOperation operation = new IsLockedOperation(name, key);
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            Future future = invocation.invoke();
            return (Boolean) future.get();
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected boolean tryLockInternal(final Data key, final long timeout, final TimeUnit timeunit) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        TryLockOperation operation = new TryLockOperation(name, key, getTimeInMillis(timeout, timeunit));
        operation.setThreadId(ThreadContext.getThreadId());
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            Future future = invocation.invoke();
            return (Boolean) future.get();
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected Set<Data> keySetInternal() {
        try {
            MapKeySetOperation mapKeySetOperation = new MapKeySetOperation(name);
            Map<Integer, Object> results = nodeEngine.getOperationService()
                    .invokeOnAllPartitions(SERVICE_NAME, mapKeySetOperation);
            Set<Data> keySet = new HashSet<Data>();
            for (Object result : results.values()) {
                Set keys = ((MapKeySet) nodeEngine.toObject(result)).getKeySet();
                keySet.addAll(keys);
            }
            return keySet;
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected Set<Data> localKeySetInternal() {
        try {
            MapKeySetOperation mapKeySetOperation = new MapKeySetOperation(name);
            Map<Integer, Object> results = nodeEngine.getOperationService()
                    .invokeOnTargetPartitions(SERVICE_NAME, mapKeySetOperation, nodeEngine.getThisAddress());
            Set<Data> keySet = new HashSet<Data>();
            for (Object result : results.values()) {
                Set keys = ((MapKeySet) nodeEngine.toObject(result)).getKeySet();
                keySet.addAll(keys);
            }
            return keySet;
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    public void flush(boolean flushAll) {
        try {
            MapFlushOperation mapFlushOperation = new MapFlushOperation(name, flushAll);
            nodeEngine.getOperationService()
                    .invokeOnAllPartitions(SERVICE_NAME, mapFlushOperation);
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected Collection<Data> valuesInternal() {
        try {
            MapValuesOperation mapValuesOperation = new MapValuesOperation(name);
            Map<Integer, Object> results = nodeEngine.getOperationService()
                    .invokeOnAllPartitions(SERVICE_NAME, mapValuesOperation);
            List<Data> values = new ArrayList<Data>();
            for (Object result : results.values()) {
                values.addAll(((MapValueCollection) nodeEngine.toObject(result)).getValues());
            }
            return values;
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    public void clearInternal() {
        try {
            ClearOperation clearOperation = new ClearOperation(name);
            clearOperation.setServiceName(SERVICE_NAME);
            nodeEngine.getOperationService()
                    .invokeOnAllPartitions(SERVICE_NAME, clearOperation);
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    protected void forceUnlockInternal(final Data key) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        ForceUnlockOperation operation = new ForceUnlockOperation(name, key);
        operation.setThreadId(ThreadContext.getThreadId());
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            Future future = invocation.invoke();
            future.get();
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    public void addMapInterceptorInternal(MapInterceptor interceptor) {
        String id = mapService.addInterceptor(name, interceptor);
        AddInterceptorOperation operation = new AddInterceptorOperation(id, interceptor, name);
        Collection<MemberImpl> members = nodeEngine.getClusterService().getMemberList();
        for (MemberImpl member : members) {
            try {
                if (member.localMember())
                    continue;
                Invocation invocation = nodeEngine.getOperationService()
                        .createInvocationBuilder(SERVICE_NAME, operation, member.getAddress()).build();
                invocation.invoke().get();
            } catch (Throwable throwable) {
                throw new HazelcastException(throwable);
            }
        }
    }

    public void removeMapInterceptorInternal(MapInterceptor interceptor) {
        String id = mapService.removeInterceptor(name, interceptor);
        RemoveInterceptorOperation operation = new RemoveInterceptorOperation(interceptor, name, id);
        Collection<MemberImpl> members = nodeEngine.getClusterService().getMemberList();
        for (Member member : members) {
            try {
                if (member.localMember())
                    continue;
                MemberImpl memberImpl = (MemberImpl) member;
                Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, memberImpl.getAddress()).build();
                invocation.invoke().get();
            } catch (Throwable throwable) {
                throw new HazelcastException(throwable);
            }
        }
    }

    protected void addLocalEntryListenerInternal(final EntryListener<Data, Data> listener) {
    }

    protected void removeEntryListenerInternal(final EntryListener listener) {
        removeEntryListenerInternal(listener, null);
    }

    protected void addEntryListenerInternal(final EntryListener listener, final Data key, final boolean includeValue) {
        EventFilter eventFilter = new EntryEventFilter(includeValue, key);
        mapService.addEventListener(listener, eventFilter, name);
    }

    protected void addEntryListenerInternal(EntryListener listener, Predicate predicate, final Data key, final boolean includeValue) {
        EventFilter eventFilter = new QueryEventFilter(includeValue, key, predicate);
        mapService.addEventListener(listener, eventFilter, name);
    }

    protected void removeEntryListenerInternal(final EntryListener listener, final Data key) {
        mapService.removeEventListener(listener, name, key);
    }

    protected Map.Entry<Data, Data> getMapEntryInternal(final Data key) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        GetMapEntryOperation getMapEntryOperation = new GetMapEntryOperation(name, key);
        getMapEntryOperation.setServiceName(SERVICE_NAME);
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, getMapEntryOperation,
                    partitionId).build();
            Future f = invocation.invoke();
            Object o = nodeEngine.toObject(f.get());
            return (Map.Entry<Data, Data>) o;
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    protected Set<Entry<Data, Data>> entrySetInternal() {
        try {
            MapEntrySetOperation mapEntrySetOperation = new MapEntrySetOperation(name);
            Map<Integer, Object> results = nodeEngine.getOperationService()
                    .invokeOnAllPartitions(SERVICE_NAME, mapEntrySetOperation);
            Set<Entry<Data, Data>> entrySet = new HashSet<Entry<Data, Data>>();
            for (Object result : results.values()) {
                Set entries = ((MapEntrySet) nodeEngine.toObject(result)).getEntrySet();
                if (entries != null)
                    entrySet.addAll(entries);
            }
            return entrySet;
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    public Data executeOnKeyInternal(Data key, EntryProcessor entryProcessor) {
        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
        EntryOperation operation = new EntryOperation(name, key, entryProcessor);
        operation.setThreadId(ThreadContext.getThreadId());
        try {
            Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId)
                    .build();
            Future future = invocation.invoke();
            return (Data) future.get();
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    public void cleanUpNearCache() {
    }

    protected Set query(final Predicate predicate, final QueryResultStream.IterationType iterationType, final boolean dataResult) {
        OperationService operationService = nodeEngine.getOperationService();
        QueryOperation operation = new QueryOperation(name, predicate);
        Collection<MemberImpl> members = nodeEngine.getClusterService().getMemberList();
        int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        Set<Integer> plist = new HashSet<Integer>(partitionCount);
        QueryResultStream result = new QueryResultStream(nodeEngine.getSerializationService(), iterationType, dataResult, true);
        try {
            List<Future> flist = new ArrayList<Future>();
            for (MemberImpl member : members) {
                Invocation invocation = operationService
                        .createInvocationBuilder(SERVICE_NAME, operation, member.getAddress()).build();
                Future future = invocation.invoke();
                flist.add(future);
            }
            for (Future future : flist) {
                QueryResult queryResult = (QueryResult) future.get();
                if (queryResult != null) {
                    final List<Integer> partitionIds = queryResult.getPartitionIds();
                    if (partitionIds != null) {
                        plist.addAll(partitionIds);
                        result.addAll(queryResult.getResult());
                    }
                }
            }
            if (plist.size() == partitionCount) {
                return result;
            }
            List<Integer> missingList = new ArrayList<Integer>();
            for (int i = 0; i < partitionCount; i++) {
                if (!plist.contains(i)) {
                    missingList.add(i);
                }
            }
            List<Future> futures = new ArrayList<Future>(missingList.size());
            for (Integer pid : missingList) {
                QueryPartitionOperation queryPartitionOperation = new QueryPartitionOperation(name, predicate);
                queryPartitionOperation.setPartitionId(pid);
                try {
                    Future f = operationService.createInvocationBuilder(SERVICE_NAME, queryPartitionOperation, pid).build().invoke();
                    futures.add(f);
                } catch (Throwable throwable) {
                    throw new HazelcastException(throwable);
                }
            }
            for (Future future : futures) {
                QueryResult queryResult = (QueryResult) future.get();
                result.addAll(queryResult.getResult());
            }
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        } finally {
            result.end();
        }
        return result;
    }

    protected Set<Data> localKeySetInternal(final Predicate predicate) {
        return null;
    }

    public void addIndex(final String attribute, final boolean ordered) {
        if (attribute == null) throw new IllegalArgumentException("attribute name cannot be null");
        try {
            AddIndexOperation mapKeySetOperation = new AddIndexOperation(name, attribute, ordered);
            Map<Integer, Object> results = nodeEngine.getOperationService()
                    .invokeOnAllPartitions(SERVICE_NAME, mapKeySetOperation);
        } catch (Throwable throwable) {
            throw new HazelcastException(throwable);
        }
    }

    public LocalMapStats getLocalMapStats() {
        return null;
    }

    /**
     * Attaches mapService and partition as a transaction participant
     * if thread is in transaction.
     *
     * @param partitionId
     * @return txnId if thread is in transaction, null otherwise
     */
    protected String attachTxnParticipant(int partitionId) {
        TransactionImpl txn = nodeEngine.getTransaction();
        String txnId = null;
        if (txn != null && txn.getStatus() == Transaction.TXN_STATUS_ACTIVE) {
            txnId = txn.getTxnId();
            txn.attachParticipant(SERVICE_NAME, partitionId);
        }
        return txnId;
    }

    protected String getCurrentTransactionId() {
        TransactionImpl txn = nodeEngine.getTransaction();
        String txnId = null;
        if (txn != null && txn.getStatus() == Transaction.TXN_STATUS_ACTIVE) {
            return txn.getTxnId();
        }
        return txnId;
    }

    protected long getTimeInMillis(final long time, final TimeUnit timeunit) {
        return timeunit != null ? timeunit.toMillis(time) : time;
    }

    public final Object getId() {
        return name;
    }

    public final String getName() {
        return name;
    }

    public final String getServiceName() {
        return SERVICE_NAME;
    }
}