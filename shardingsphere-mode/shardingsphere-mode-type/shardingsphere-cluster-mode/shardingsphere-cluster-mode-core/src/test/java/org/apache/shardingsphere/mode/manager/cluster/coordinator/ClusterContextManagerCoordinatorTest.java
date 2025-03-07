/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.mode.manager.cluster.coordinator;

import org.apache.shardingsphere.authority.config.AuthorityRuleConfiguration;
import org.apache.shardingsphere.authority.rule.AuthorityRule;
import org.apache.shardingsphere.infra.config.RuleConfiguration;
import org.apache.shardingsphere.infra.config.algorithm.ShardingSphereAlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.mode.ModeConfiguration;
import org.apache.shardingsphere.infra.config.mode.PersistRepositoryConfiguration;
import org.apache.shardingsphere.infra.config.props.ConfigurationProperties;
import org.apache.shardingsphere.infra.config.props.ConfigurationPropertyKey;
import org.apache.shardingsphere.infra.datasource.props.DataSourceProperties;
import org.apache.shardingsphere.infra.datasource.props.DataSourcePropertiesCreator;
import org.apache.shardingsphere.infra.executor.kernel.ExecutorEngine;
import org.apache.shardingsphere.infra.federation.optimizer.context.OptimizerContext;
import org.apache.shardingsphere.infra.federation.optimizer.metadata.FederationSchemaMetaData;
import org.apache.shardingsphere.infra.instance.definition.InstanceDefinition;
import org.apache.shardingsphere.infra.instance.definition.InstanceType;
import org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.infra.metadata.resource.ShardingSphereResource;
import org.apache.shardingsphere.infra.metadata.rule.ShardingSphereRuleMetaData;
import org.apache.shardingsphere.infra.metadata.schema.QualifiedSchema;
import org.apache.shardingsphere.infra.metadata.schema.ShardingSphereSchema;
import org.apache.shardingsphere.infra.metadata.schema.model.TableMetaData;
import org.apache.shardingsphere.infra.metadata.user.ShardingSphereUser;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.infra.rule.identifier.type.StatusContainedRule;
import org.apache.shardingsphere.infra.state.StateType;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.manager.ContextManagerBuilderParameter;
import org.apache.shardingsphere.mode.manager.cluster.ClusterContextManagerBuilder;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.authority.event.AuthorityChangedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.config.event.datasource.DataSourceChangedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.config.event.props.PropertiesChangedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.config.event.rule.GlobalRuleConfigurationsChangedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.config.event.rule.RuleConfigurationsChangedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.config.event.schema.SchemaChangedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.metadata.event.SchemaAddedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.metadata.event.SchemaDeletedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.status.compute.event.LabelsEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.status.compute.event.StateEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.status.compute.event.WorkerIdEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.status.storage.event.DisabledStateChangedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.status.storage.event.PrimaryStateChangedEvent;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.mode.metadata.persist.MetaDataPersistService;
import org.apache.shardingsphere.mode.repository.cluster.ClusterPersistRepositoryConfiguration;
import org.apache.shardingsphere.parser.rule.SQLParserRule;
import org.apache.shardingsphere.test.mock.MockedDataSource;
import org.apache.shardingsphere.transaction.context.TransactionContexts;
import org.apache.shardingsphere.transaction.rule.TransactionRule;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public final class ClusterContextManagerCoordinatorTest {
    
    private ClusterContextManagerCoordinator coordinator;
    
    private ContextManager contextManager;
    
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private MetaDataPersistService metaDataPersistService;
    
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ShardingSphereMetaData metaData;
    
    @Mock
    private ShardingSphereRuleMetaData globalRuleMetaData;
    
    @Before
    public void setUp() throws SQLException {
        PersistRepositoryConfiguration persistRepositoryConfig = new ClusterPersistRepositoryConfiguration("TEST", "", "", new Properties());
        ModeConfiguration modeConfig = new ModeConfiguration("Cluster", persistRepositoryConfig, false);
        ClusterContextManagerBuilder builder = new ClusterContextManagerBuilder();
        contextManager = builder.build(ContextManagerBuilderParameter.builder().modeConfig(modeConfig).schemaConfigs(new HashMap<>())
                .globalRuleConfigs(new LinkedList<>()).props(new Properties()).instanceDefinition(new InstanceDefinition(InstanceType.PROXY, 3307)).build());
        assertTrue(contextManager.getMetaDataContexts().getMetaDataPersistService().isPresent());
        contextManager.renewMetaDataContexts(new MetaDataContexts(contextManager.getMetaDataContexts().getMetaDataPersistService().get(), createMetaDataMap(), globalRuleMetaData,
                mock(ExecutorEngine.class), createOptimizerContext(), new ConfigurationProperties(new Properties())));
        contextManager.renewTransactionContexts(mock(TransactionContexts.class, RETURNS_DEEP_STUBS));
        coordinator = new ClusterContextManagerCoordinator(metaDataPersistService, contextManager, mock(RegistryCenter.class));
    }
    
    @Test
    public void assertSchemaAdd() throws SQLException {
        SchemaAddedEvent event = new SchemaAddedEvent("schema_add");
        when(metaDataPersistService.getDataSourceService().load("schema_add")).thenReturn(getDataSourcePropertiesMap());
        when(metaDataPersistService.getSchemaRuleService().load("schema_add")).thenReturn(Collections.emptyList());
        coordinator.renew(event);
        assertNotNull(contextManager.getMetaDataContexts().getMetaData("schema_add"));
        assertNotNull(contextManager.getMetaDataContexts().getMetaData("schema_add").getResource().getDataSources());
    }
    
    private Map<String, DataSourceProperties> getDataSourcePropertiesMap() {
        MockedDataSource dataSource = new MockedDataSource();
        Map<String, DataSourceProperties> result = new LinkedHashMap<>(3, 1);
        result.put("primary_ds", DataSourcePropertiesCreator.create(dataSource));
        result.put("ds_0", DataSourcePropertiesCreator.create(dataSource));
        result.put("ds_1", DataSourcePropertiesCreator.create(dataSource));
        return result;
    }
    
    @Test
    public void assertSchemaDelete() {
        SchemaDeletedEvent event = new SchemaDeletedEvent("schema");
        coordinator.renew(event);
        assertNull(contextManager.getMetaDataContexts().getMetaData("schema"));
    }
    
    @Test
    public void assertPropertiesChanged() {
        Properties properties = new Properties();
        properties.setProperty(ConfigurationPropertyKey.SQL_SHOW.getKey(), "true");
        PropertiesChangedEvent event = new PropertiesChangedEvent(properties);
        coordinator.renew(event);
        assertThat(contextManager.getMetaDataContexts().getProps().getProps().getProperty(ConfigurationPropertyKey.SQL_SHOW.getKey()), is("true"));
    }
    
    @Test
    public void assertSchemaChanged() {
        TableMetaData changedTableMetaData = new TableMetaData("t_order");
        SchemaChangedEvent event = new SchemaChangedEvent("schema", changedTableMetaData, null);
        coordinator.renew(event);
        assertTrue(contextManager.getMetaDataContexts().getAllSchemaNames().contains("schema"));
        verify(contextManager.getMetaDataContexts().getMetaData("schema").getSchema()).put(eq("t_order"), eq(event.getChangedTableMetaData()));
    }
    
    @Test
    public void assertRuleConfigurationsChanged() {
        assertThat(contextManager.getMetaDataContexts().getMetaData("schema"), is(metaData));
        RuleConfigurationsChangedEvent event = new RuleConfigurationsChangedEvent("schema", "0", new LinkedList<>());
        coordinator.renew(event);
        assertThat(contextManager.getMetaDataContexts().getMetaData("schema"), not(metaData));
    }
    
    @Test
    public void assertDisableStateChanged() {
        DisabledStateChangedEvent event = new DisabledStateChangedEvent(new QualifiedSchema("schema.ds_0"), true);
        coordinator.renew(event);
    }
    
    @Test
    public void assertDataSourceChanged() {
        DataSourceChangedEvent event = new DataSourceChangedEvent("schema", "0", getChangedDataSourcePropertiesMap());
        coordinator.renew(event);
        assertTrue(contextManager.getMetaDataContexts().getMetaData("schema").getResource().getDataSources().containsKey("ds_2"));
    }
    
    private Map<String, DataSourceProperties> getChangedDataSourcePropertiesMap() {
        MockedDataSource dataSource = new MockedDataSource();
        Map<String, DataSourceProperties> result = new LinkedHashMap<>(3, 1);
        result.put("primary_ds", DataSourcePropertiesCreator.create(dataSource));
        result.put("ds_1", DataSourcePropertiesCreator.create(dataSource));
        result.put("ds_2", DataSourcePropertiesCreator.create(dataSource));
        return result;
    }
    
    @Test
    public void assertGlobalRuleConfigurationsChanged() {
        GlobalRuleConfigurationsChangedEvent event = new GlobalRuleConfigurationsChangedEvent(getChangedGlobalRuleConfigurations());
        coordinator.renew(event);
        assertThat(contextManager.getMetaDataContexts().getGlobalRuleMetaData(), not(globalRuleMetaData));
        assertThat(contextManager.getMetaDataContexts().getGlobalRuleMetaData().getRules().size(), is(3));
        assertThat(contextManager.getMetaDataContexts().getGlobalRuleMetaData().getRules().stream().filter(each -> each instanceof AuthorityRule).count(), is(1L));
        assertThat(contextManager.getMetaDataContexts().getGlobalRuleMetaData().getRules().stream().filter(each -> each instanceof TransactionRule).count(), is(1L));
        assertThat(contextManager.getMetaDataContexts().getGlobalRuleMetaData().getRules().stream().filter(each -> each instanceof SQLParserRule).count(), is(1L));
    }
    
    private Collection<RuleConfiguration> getChangedGlobalRuleConfigurations() {
        RuleConfiguration authorityRuleConfig = new AuthorityRuleConfiguration(getShardingSphereUsers(), new ShardingSphereAlgorithmConfiguration("NATIVE", new Properties()));
        return Collections.singleton(authorityRuleConfig);
    }
    
    private Collection<ShardingSphereUser> getShardingSphereUsers() {
        Collection<ShardingSphereUser> result = new LinkedList<>();
        result.add(new ShardingSphereUser("root", "root", "%"));
        result.add(new ShardingSphereUser("sharding", "sharding", "localhost"));
        return result;
    }
    
    @Test
    public void assertAuthorityChanged() {
        when(contextManager.getMetaDataContexts().getGlobalRuleMetaData().getRules()).thenReturn(createAuthorityRule());
        AuthorityChangedEvent event = new AuthorityChangedEvent(getShardingSphereUsers());
        coordinator.renew(event);
        Optional<AuthorityRule> authorityRule = contextManager.getMetaDataContexts().getGlobalRuleMetaData().getRules()
                .stream().filter(each -> each instanceof AuthorityRule).findAny().map(each -> (AuthorityRule) each);
        assertTrue(authorityRule.isPresent());
        assertNotNull(authorityRule.get().findUser(new ShardingSphereUser("root", "root", "%").getGrantee()));
    }
    
    private Collection<ShardingSphereRule> createAuthorityRule() {
        AuthorityRuleConfiguration ruleConfig = new AuthorityRuleConfiguration(Collections.emptyList(), new ShardingSphereAlgorithmConfiguration("ALL_PRIVILEGES_PERMITTED", new Properties()));
        AuthorityRule authorityRule = new AuthorityRule(ruleConfig, contextManager.getMetaDataContexts().getMetaDataMap());
        return Collections.singleton(authorityRule);
    }
    
    private Map<String, ShardingSphereMetaData> createMetaDataMap() {
        when(metaData.getName()).thenReturn("schema");
        ShardingSphereResource resource = mock(ShardingSphereResource.class);
        when(metaData.getResource()).thenReturn(resource);
        ShardingSphereSchema schema = mock(ShardingSphereSchema.class);
        when(metaData.getSchema()).thenReturn(schema);
        when(metaData.getRuleMetaData().getRules()).thenReturn(new LinkedList<>());
        when(metaData.getRuleMetaData().getConfigurations()).thenReturn(new LinkedList<>());
        return new HashMap<>(Collections.singletonMap("schema", metaData));
    }
    
    private OptimizerContext createOptimizerContext() {
        OptimizerContext result = mock(OptimizerContext.class, RETURNS_DEEP_STUBS);
        Map<String, FederationSchemaMetaData> schemas = new HashMap<>(1, 1);
        schemas.put("schema", new FederationSchemaMetaData("schema", Collections.emptyMap()));
        when(result.getFederationMetaData().getSchemas()).thenReturn(schemas);
        return result;
    }
    
    @Test
    public void assertRenewPrimaryDataSourceName() {
        Collection<ShardingSphereRule> rules = new LinkedList<>();
        StatusContainedRule mockStatusContainedRule = mock(StatusContainedRule.class);
        rules.add(mockStatusContainedRule);
        ShardingSphereRuleMetaData mockShardingSphereRuleMetaData = new ShardingSphereRuleMetaData(new LinkedList<>(), rules);
        ShardingSphereMetaData mockShardingSphereMetaData = mock(ShardingSphereMetaData.class);
        when(mockShardingSphereMetaData.getRuleMetaData()).thenReturn(mockShardingSphereRuleMetaData);
        contextManager.getMetaDataContexts().getMetaDataMap().put("schema", mockShardingSphereMetaData);
        PrimaryStateChangedEvent mockPrimaryStateChangedEvent = new PrimaryStateChangedEvent(new QualifiedSchema("schema.test_ds"), "test_ds");
        coordinator.renew(mockPrimaryStateChangedEvent);
        verify(mockStatusContainedRule).updateStatus(any());
    }
    
    @Test
    public void assertRenewInstanceStatus() {
        Collection<String> testStates = new LinkedList<>();
        testStates.add(StateType.OK.name());
        testStates.add(StateType.LOCK.name());
        StateEvent mockStateEvent = new StateEvent(contextManager.getInstanceContext().getInstance().getInstanceDefinition().getInstanceId().getId(), testStates);
        coordinator.renew(mockStateEvent);
        assertThat(contextManager.getInstanceContext().getInstance().getStatus(), is(testStates));
        testStates.add(StateType.CIRCUIT_BREAK.name());
        coordinator.renew(mockStateEvent);
        assertThat(contextManager.getInstanceContext().getState().getCurrentState(), is(StateType.CIRCUIT_BREAK));
    }
    
    @Test
    public void assertRenewWorkerIdChange() {
        WorkerIdEvent mockWorkerIdEvent = new WorkerIdEvent(contextManager.getInstanceContext().getInstance().getInstanceDefinition().getInstanceId().getId(), 12223L);
        coordinator.renew(mockWorkerIdEvent);
        assertThat(contextManager.getInstanceContext().getWorkerId(), is(12223L));
    }
    
    @Test
    public void assertRenewInstanceLabels() {
        Collection<String> labels = new LinkedList<>();
        labels.add("test");
        LabelsEvent mockLabelsEvent = new LabelsEvent(contextManager.getInstanceContext().getInstance().getInstanceDefinition().getInstanceId().getId(), labels);
        coordinator.renew(mockLabelsEvent);
        assertThat(contextManager.getInstanceContext().getInstance().getLabels(), is(labels));
    }
} 
