/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.kafka.dynamicconfiguration;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.strimzi.api.kafka.model.kafka.KafkaClusterSpec;
import io.strimzi.api.kafka.model.kafka.KafkaResources;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.TestConstants;
import io.strimzi.systemtest.annotations.IsolatedTest;
import io.strimzi.systemtest.cli.KafkaCmdClient;
import io.strimzi.systemtest.kafkaclients.externalClients.ExternalKafkaClient;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.storage.TestStorage;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.templates.crd.KafkaUserTemplates;
import io.strimzi.systemtest.templates.specific.ScraperTemplates;
import io.strimzi.systemtest.utils.RollingUpdateUtils;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.TestKafkaVersion;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static io.strimzi.systemtest.TestConstants.DYNAMIC_CONFIGURATION;
import static io.strimzi.systemtest.TestConstants.EXTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.TestConstants.NODEPORT_SUPPORTED;
import static io.strimzi.systemtest.TestConstants.REGRESSION;
import static io.strimzi.systemtest.TestConstants.ROLLING_UPDATE;
import static io.strimzi.systemtest.resources.ResourceManager.kubeClient;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DynamicConfST is responsible for verify that if we change dynamic Kafka configuration it will not
 * trigger rolling update.
 * Isolated -> for each test case we have different configuration of Kafka resource
 */
@Tag(REGRESSION)
@Tag(DYNAMIC_CONFIGURATION)
public class DynamicConfST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(DynamicConfST.class);
    private static final int KAFKA_REPLICAS = 3;

    private Map<String, Object> kafkaConfig;

    @IsolatedTest("Using more tha one Kafka cluster in one namespace")
    void testSimpleDynamicConfiguration(ExtensionContext extensionContext) {
        final TestStorage testStorage = storageMap.get(extensionContext);

        String clusterName = testStorage.getClusterName();
        String scraperName = testStorage.getScraperName();

        Map<String, Object> deepCopyOfShardKafkaConfig = kafkaConfig.entrySet().stream()
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));

        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, KAFKA_REPLICAS, 1)
            .editMetadata()
                .withNamespace(Environment.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withConfig(deepCopyOfShardKafkaConfig)
                .endKafka()
            .endSpec()
            .build(),
            ScraperTemplates.scraperPod(Environment.TEST_SUITE_NAMESPACE, scraperName).build()
        );

        String scraperPodName = kubeClient().listPodsByPrefixInName(Environment.TEST_SUITE_NAMESPACE, scraperName).get(0).getMetadata().getName();

        for (String cmName : StUtils.getKafkaConfigurationConfigMaps(clusterName, KAFKA_REPLICAS)) {
            String kafkaConfiguration = kubeClient().getConfigMap(Environment.TEST_SUITE_NAMESPACE, cmName).getData().get("server.config");
            assertThat(kafkaConfiguration, containsString("offsets.topic.replication.factor=1"));
            assertThat(kafkaConfiguration, containsString("transaction.state.log.replication.factor=1"));
            assertThat(kafkaConfiguration, containsString("log.message.format.version=" + TestKafkaVersion.getKafkaVersionsInMap().get(Environment.ST_KAFKA_VERSION).messageVersion()));
        }

        String kafkaConfigurationFromPod = KafkaCmdClient.describeKafkaBrokerUsingPodCli(Environment.TEST_SUITE_NAMESPACE, scraperPodName, KafkaResources.plainBootstrapAddress(clusterName), 0);
        assertThat(kafkaConfigurationFromPod, containsString("Dynamic configs for broker 0 are:\n"));

        deepCopyOfShardKafkaConfig.put("unclean.leader.election.enable", true);

        updateAndVerifyDynConf(Environment.TEST_SUITE_NAMESPACE, clusterName, deepCopyOfShardKafkaConfig);

        kafkaConfigurationFromPod = KafkaCmdClient.describeKafkaBrokerUsingPodCli(Environment.TEST_SUITE_NAMESPACE, scraperPodName, KafkaResources.plainBootstrapAddress(clusterName), 0);
        assertThat(kafkaConfigurationFromPod, containsString("unclean.leader.election.enable=" + true));

        LOGGER.info("Verifying values after update");

        for (String cmName : StUtils.getKafkaConfigurationConfigMaps(clusterName, KAFKA_REPLICAS)) {
            String kafkaConfiguration = kubeClient().getConfigMap(Environment.TEST_SUITE_NAMESPACE, cmName).getData().get("server.config");
            assertThat(kafkaConfiguration, containsString("offsets.topic.replication.factor=1"));
            assertThat(kafkaConfiguration, containsString("transaction.state.log.replication.factor=1"));
            assertThat(kafkaConfiguration, containsString("log.message.format.version=" + TestKafkaVersion.getKafkaVersionsInMap().get(Environment.ST_KAFKA_VERSION).messageVersion()));
            assertThat(kafkaConfiguration, containsString("unclean.leader.election.enable=true"));
        }
    }

    @Tag(NODEPORT_SUPPORTED)
    @Tag(ROLLING_UPDATE)
    @IsolatedTest("Using more tha one Kafka cluster in one namespace")
    void testUpdateToExternalListenerCausesRollingRestart(ExtensionContext extensionContext) {
        final TestStorage testStorage = storageMap.get(extensionContext);
        String clusterName = testStorage.getClusterName();
        String scraperName = testStorage.getScraperName();


        Map<String, Object> deepCopyOfShardKafkaConfig = kafkaConfig.entrySet().stream()
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        LabelSelector kafkaSelector = KafkaResource.getLabelSelector(clusterName, KafkaResources.kafkaStatefulSetName(clusterName));

        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, KAFKA_REPLICAS, 1)
            .editMetadata()
                .withNamespace(Environment.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                                .withName(TestConstants.PLAIN_LISTENER_DEFAULT_NAME)
                                .withPort(9092)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .build(),
                            new GenericKafkaListenerBuilder()
                                .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                                .withPort(9093)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(true)
                                .build(),
                            new GenericKafkaListenerBuilder()
                                .withName(TestConstants.EXTERNAL_LISTENER_DEFAULT_NAME)
                                .withPort(9094)
                                .withType(KafkaListenerType.NODEPORT)
                                .withTls(false)
                                .build())
                    .withConfig(deepCopyOfShardKafkaConfig)
                .endKafka()
            .endSpec()
            .build(),
            ScraperTemplates.scraperPod(Environment.TEST_SUITE_NAMESPACE, scraperName).build()
        );

        String scraperPodName = kubeClient().listPodsByPrefixInName(Environment.TEST_SUITE_NAMESPACE, scraperName).get(0).getMetadata().getName();
        String kafkaConfigurationFromPod = KafkaCmdClient.describeKafkaBrokerUsingPodCli(Environment.TEST_SUITE_NAMESPACE, scraperPodName, KafkaResources.plainBootstrapAddress(clusterName), 0);

        assertThat(kafkaConfigurationFromPod, containsString("Dynamic configs for broker 0 are:\n"));

        deepCopyOfShardKafkaConfig.put("unclean.leader.election.enable", true);

        updateAndVerifyDynConf(Environment.TEST_SUITE_NAMESPACE, clusterName, deepCopyOfShardKafkaConfig);

        kafkaConfigurationFromPod = KafkaCmdClient.describeKafkaBrokerUsingPodCli(Environment.TEST_SUITE_NAMESPACE, scraperPodName, KafkaResources.plainBootstrapAddress(clusterName), 0);
        assertThat(kafkaConfigurationFromPod, containsString("unclean.leader.election.enable=" + true));

        // Edit listeners - this should cause RU (because of new crts)
        Map<String, String> kafkaPods = PodUtils.podSnapshot(Environment.TEST_SUITE_NAMESPACE, kafkaSelector);
        LOGGER.info("Updating listeners of Kafka cluster");

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> {
            k.getSpec().getKafka().setListeners(Arrays.asList(
                new GenericKafkaListenerBuilder()
                    .withName(TestConstants.PLAIN_LISTENER_DEFAULT_NAME)
                    .withPort(9092)
                    .withType(KafkaListenerType.INTERNAL)
                    .withTls(false)
                    .build(),
                new GenericKafkaListenerBuilder()
                    .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                    .withPort(9093)
                    .withType(KafkaListenerType.INTERNAL)
                    .withTls(true)
                    .build(),
                new GenericKafkaListenerBuilder()
                    .withName(TestConstants.EXTERNAL_LISTENER_DEFAULT_NAME)
                    .withPort(9094)
                    .withType(KafkaListenerType.NODEPORT)
                    .withTls(true)
                    .build()
            ));
        }, Environment.TEST_SUITE_NAMESPACE);

        RollingUpdateUtils.waitTillComponentHasRolled(Environment.TEST_SUITE_NAMESPACE, kafkaSelector, KAFKA_REPLICAS, kafkaPods);
        assertThat(RollingUpdateUtils.componentHasRolled(Environment.TEST_SUITE_NAMESPACE, kafkaSelector, kafkaPods), is(true));

        kafkaConfigurationFromPod = KafkaCmdClient.describeKafkaBrokerUsingPodCli(Environment.TEST_SUITE_NAMESPACE, scraperPodName, KafkaResources.plainBootstrapAddress(clusterName), 0);
        assertThat(kafkaConfigurationFromPod, containsString("Dynamic configs for broker 0 are:\n"));

        deepCopyOfShardKafkaConfig.put("compression.type", "snappy");

        updateAndVerifyDynConf(Environment.TEST_SUITE_NAMESPACE, clusterName, deepCopyOfShardKafkaConfig);

        kafkaConfigurationFromPod = KafkaCmdClient.describeKafkaBrokerUsingPodCli(Environment.TEST_SUITE_NAMESPACE, scraperPodName, KafkaResources.plainBootstrapAddress(clusterName), 0);
        assertThat(kafkaConfigurationFromPod, containsString("compression.type=snappy"));

        kafkaConfigurationFromPod = KafkaCmdClient.describeKafkaBrokerUsingPodCli(Environment.TEST_SUITE_NAMESPACE, scraperPodName, KafkaResources.plainBootstrapAddress(clusterName), 0);
        assertThat(kafkaConfigurationFromPod, containsString("Dynamic configs for broker 0 are:\n"));

        deepCopyOfShardKafkaConfig.put("unclean.leader.election.enable", true);

        updateAndVerifyDynConf(Environment.TEST_SUITE_NAMESPACE, clusterName, deepCopyOfShardKafkaConfig);

        kafkaConfigurationFromPod = KafkaCmdClient.describeKafkaBrokerUsingPodCli(Environment.TEST_SUITE_NAMESPACE, scraperPodName, KafkaResources.plainBootstrapAddress(clusterName), 0);
        assertThat(kafkaConfigurationFromPod, containsString("unclean.leader.election.enable=" + true));

        // Remove external listeners (node port) - this should cause RU (we need to update advertised.listeners)
        // Other external listeners cases are rolling because of crts
        kafkaPods = PodUtils.podSnapshot(Environment.TEST_SUITE_NAMESPACE, kafkaSelector);
        LOGGER.info("Updating listeners of Kafka cluster");

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> {
            k.getSpec().getKafka().setListeners(Arrays.asList(
                new GenericKafkaListenerBuilder()
                    .withName(TestConstants.PLAIN_LISTENER_DEFAULT_NAME)
                    .withPort(9092)
                    .withType(KafkaListenerType.INTERNAL)
                    .withTls(false)
                    .build(),
                new GenericKafkaListenerBuilder()
                    .withName(TestConstants.EXTERNAL_LISTENER_DEFAULT_NAME)
                    .withPort(9094)
                    .withType(KafkaListenerType.NODEPORT)
                    .withTls(true)
                    .build()
            ));
        }, Environment.TEST_SUITE_NAMESPACE);

        RollingUpdateUtils.waitTillComponentHasRolled(Environment.TEST_SUITE_NAMESPACE, kafkaSelector, KAFKA_REPLICAS, kafkaPods);
        assertThat(RollingUpdateUtils.componentHasRolled(Environment.TEST_SUITE_NAMESPACE, kafkaSelector, kafkaPods), is(true));

        kafkaConfigurationFromPod = KafkaCmdClient.describeKafkaBrokerUsingPodCli(Environment.TEST_SUITE_NAMESPACE, scraperPodName, KafkaResources.plainBootstrapAddress(clusterName), 0);
        assertThat(kafkaConfigurationFromPod, containsString("Dynamic configs for broker 0 are:\n"));

        deepCopyOfShardKafkaConfig.put("unclean.leader.election.enable", false);

        updateAndVerifyDynConf(Environment.TEST_SUITE_NAMESPACE, clusterName, deepCopyOfShardKafkaConfig);

        kafkaConfigurationFromPod = KafkaCmdClient.describeKafkaBrokerUsingPodCli(Environment.TEST_SUITE_NAMESPACE, scraperPodName, KafkaResources.plainBootstrapAddress(clusterName), 0);
        assertThat(kafkaConfigurationFromPod, containsString("unclean.leader.election.enable=" + false));
    }

    @IsolatedTest("Using more tha one Kafka cluster in one namespace")
    @Tag(NODEPORT_SUPPORTED)
    @Tag(EXTERNAL_CLIENTS_USED)
    @Tag(ROLLING_UPDATE)
    void testUpdateToExternalListenerCausesRollingRestartUsingExternalClients(ExtensionContext extensionContext) {
        final TestStorage testStorage = storageMap.get(extensionContext);
        String clusterName = testStorage.getClusterName();
        String topicName = testStorage.getTopicName();
        String userName = testStorage.getKafkaUsername();
        Map<String, Object> deepCopyOfShardKafkaConfig = kafkaConfig.entrySet().stream()
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        LabelSelector kafkaSelector = KafkaResource.getLabelSelector(clusterName, KafkaResources.kafkaStatefulSetName(clusterName));

        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, KAFKA_REPLICAS, 1)
            .editMetadata()
                .withNamespace(Environment.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(TestConstants.EXTERNAL_LISTENER_DEFAULT_NAME)
                            .withPort(9094)
                            .withType(KafkaListenerType.NODEPORT)
                            .withTls(false)
                        .build())
                    .withConfig(deepCopyOfShardKafkaConfig)
                .endKafka()
            .endSpec()
            .build());

        Map<String, String> kafkaPods = PodUtils.podSnapshot(Environment.TEST_SUITE_NAMESPACE, kafkaSelector);

        resourceManager.createResourceWithWait(extensionContext, KafkaTopicTemplates.topic(clusterName, topicName, Environment.TEST_SUITE_NAMESPACE).build());
        resourceManager.createResourceWithWait(extensionContext, KafkaUserTemplates.tlsUser(Environment.TEST_SUITE_NAMESPACE, clusterName, userName).build());

        ExternalKafkaClient externalKafkaClientTls = new ExternalKafkaClient.Builder()
            .withTopicName(topicName)
            .withNamespaceName(Environment.TEST_SUITE_NAMESPACE)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withKafkaUsername(userName)
            .withSecurityProtocol(SecurityProtocol.SSL)
            .withListenerName(TestConstants.EXTERNAL_LISTENER_DEFAULT_NAME)
            .build();

        ExternalKafkaClient externalKafkaClientPlain = new ExternalKafkaClient.Builder()
            .withTopicName(topicName)
            .withNamespaceName(Environment.TEST_SUITE_NAMESPACE)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withSecurityProtocol(SecurityProtocol.PLAINTEXT)
            .withListenerName(TestConstants.EXTERNAL_LISTENER_DEFAULT_NAME)
            .build();

        externalKafkaClientPlain.verifyProducedAndConsumedMessages(
            externalKafkaClientPlain.sendMessagesPlain(),
            externalKafkaClientPlain.receiveMessagesPlain()
        );

        assertThrows(Exception.class, () -> {
            externalKafkaClientTls.sendMessagesTls();
            externalKafkaClientTls.receiveMessagesTls();
            LOGGER.error("Producer & Consumer did not send and receive messages because external listener is set to plain communication");
        });

        LOGGER.info("Updating listeners of Kafka cluster");
        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> {
            k.getSpec().getKafka().setListeners(Arrays.asList(
                new GenericKafkaListenerBuilder()
                    .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                    .withPort(9093)
                    .withType(KafkaListenerType.INTERNAL)
                    .withTls(true)
                    .build(),
                new GenericKafkaListenerBuilder()
                    .withName(TestConstants.EXTERNAL_LISTENER_DEFAULT_NAME)
                    .withPort(9094)
                    .withType(KafkaListenerType.NODEPORT)
                    .withTls(true)
                    .withNewKafkaListenerAuthenticationTlsAuth()
                    .endKafkaListenerAuthenticationTlsAuth()
                    .build()
            ));
        }, Environment.TEST_SUITE_NAMESPACE);

        // TODO: remove it ?
        kafkaPods = RollingUpdateUtils.waitTillComponentHasRolled(Environment.TEST_SUITE_NAMESPACE, kafkaSelector, KAFKA_REPLICAS, kafkaPods);

        externalKafkaClientTls.verifyProducedAndConsumedMessages(
            externalKafkaClientTls.sendMessagesTls() + MESSAGE_COUNT,
            externalKafkaClientTls.receiveMessagesTls()
        );

        assertThrows(Exception.class, () -> {
            externalKafkaClientPlain.sendMessagesPlain();
            externalKafkaClientPlain.receiveMessagesPlain();
            LOGGER.error("Producer & Consumer did not send and receive messages because external listener is set to tls communication");
        });

        LOGGER.info("Updating listeners of Kafka cluster");
        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> {
            k.getSpec().getKafka().setListeners(Collections.singletonList(
                new GenericKafkaListenerBuilder()
                    .withName(TestConstants.EXTERNAL_LISTENER_DEFAULT_NAME)
                    .withPort(9094)
                    .withType(KafkaListenerType.NODEPORT)
                    .withTls(false)
                    .build()
            ));
        }, Environment.TEST_SUITE_NAMESPACE);

        RollingUpdateUtils.waitTillComponentHasRolled(Environment.TEST_SUITE_NAMESPACE, kafkaSelector, KAFKA_REPLICAS, kafkaPods);

        assertThrows(Exception.class, () -> {
            externalKafkaClientTls.sendMessagesTls();
            externalKafkaClientTls.receiveMessagesTls();
            LOGGER.error("Producer & Consumer did not send and receive messages because external listener is set to plain communication");
        });

        externalKafkaClientPlain.verifyProducedAndConsumedMessages(
            externalKafkaClientPlain.sendMessagesPlain() + MESSAGE_COUNT,
            externalKafkaClientPlain.receiveMessagesPlain()
        );
    }

    /**
     * UpdateAndVerifyDynConf, change the kafka configuration and verify that no rolling update were triggered
     * @param namespaceName name of the namespace
     * @param kafkaConfig specific kafka configuration, which will be changed
     */
    private void updateAndVerifyDynConf(final String namespaceName, String clusterName, Map<String, Object> kafkaConfig) {
        LabelSelector kafkaSelector = KafkaResource.getLabelSelector(clusterName, KafkaResources.kafkaStatefulSetName(clusterName));
        Map<String, String> kafkaPods = PodUtils.podSnapshot(namespaceName, kafkaSelector);

        LOGGER.info("Updating configuration of Kafka cluster");
        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> {
            KafkaClusterSpec kafkaClusterSpec = k.getSpec().getKafka();
            kafkaClusterSpec.setConfig(kafkaConfig);
        }, namespaceName);

        PodUtils.verifyThatRunningPodsAreStable(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName));
        assertThat(RollingUpdateUtils.componentHasRolled(namespaceName, kafkaSelector, kafkaPods), is(false));
    }

    @BeforeEach
    void setupEach() {
        kafkaConfig = new HashMap<>();
        kafkaConfig.put("offsets.topic.replication.factor", "1");
        kafkaConfig.put("transaction.state.log.replication.factor", "1");
        kafkaConfig.put("log.message.format.version", TestKafkaVersion.getKafkaVersionsInMap().get(Environment.ST_KAFKA_VERSION).messageVersion());
    }

    @BeforeAll
    void setup(ExtensionContext extensionContext) {
        this.clusterOperator = this.clusterOperator
            .defaultInstallation(extensionContext)
            .createInstallation()
            .runInstallation();
    }
}
