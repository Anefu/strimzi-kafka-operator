/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.security.oauth;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.strimzi.api.kafka.model.connect.KafkaConnectResources;
import io.strimzi.api.kafka.model.kafka.KafkaResources;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListener;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationOAuth;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.annotations.FIPSNotSupported;
import io.strimzi.systemtest.annotations.IsolatedTest;
import io.strimzi.systemtest.annotations.ParallelTest;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClients;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClientsBuilder;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.storage.TestStorage;
import io.strimzi.systemtest.templates.crd.KafkaConnectTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.RollingUpdateUtils;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.JobUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.rmi.UnexpectedException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.strimzi.systemtest.TestConstants.ARM64_UNSUPPORTED;
import static io.strimzi.systemtest.TestConstants.CONNECT;
import static io.strimzi.systemtest.TestConstants.OAUTH;
import static io.strimzi.systemtest.TestConstants.REGRESSION;
import static io.strimzi.systemtest.resources.ResourceManager.kubeClient;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag(OAUTH)
@Tag(REGRESSION)
@Tag(ARM64_UNSUPPORTED)
@FIPSNotSupported("Keycloak is not customized to run on FIPS env - https://github.com/strimzi/strimzi-kafka-operator/issues/8331")
public class OauthScopeST extends OauthAbstractST {
    
    private final String oauthClusterName = "oauth-cluster-scope-name";
    private final String scopeListener = "scopelist";
    private final String scopeListenerPort = "9098";
    private final String additionalOauthConfig =
        "sasl.mechanism = PLAIN\n" +
        "security.protocol = SASL_PLAINTEXT\n" +
        "sasl.jaas.config = org.apache.kafka.common.security.plain.PlainLoginModule required username=\"kafka-client\" password=\"kafka-client-secret\" ;";

    @ParallelTest
    @Tag(CONNECT)
    void testScopeKafkaConnectSetIncorrectly(ExtensionContext extensionContext) {
        final TestStorage testStorage = storageMap.get(extensionContext);
        final String clusterName = testStorage.getClusterName();

        // SCOPE TESTING
        resourceManager.createResourceWithoutWait(extensionContext, KafkaConnectTemplates.kafkaConnect(clusterName, clusterName, 1)
            .editMetadata()
                .withNamespace(Environment.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withBootstrapServers(KafkaResources.bootstrapServiceName(oauthClusterName) + ":" + scopeListenerPort)
                .withConfig(connectorConfig)
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .withNewKafkaClientAuthenticationOAuth()
                    .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                    .withClientId("kafka-client")
                    .withNewClientSecret()
                        .withSecretName(OAUTH_KAFKA_CLIENT_SECRET)
                        .withKey(OAUTH_KEY)
                    .endClientSecret()
                    // scope set in-correctly regarding to the scope-test realm
                    .withScope(null)
                .endKafkaClientAuthenticationOAuth()
                .withTls(null)
                .endSpec()
            .build());

        String kafkaConnectPodName = PodUtils.getPodsByPrefixInNameWithDynamicWait(Environment.TEST_SUITE_NAMESPACE, KafkaConnectResources.deploymentName(clusterName)).get(0).getMetadata().getName();

        // we except that "Token validation failed: Custom claim check failed because we specify scope='null'"
        StUtils.waitUntilLogFromPodContainsString(Environment.TEST_SUITE_NAMESPACE, kafkaConnectPodName, KafkaConnectResources.deploymentName(clusterName), "30s", "Token validation failed: Custom claim check failed");
    }

    @ParallelTest
    @Tag(CONNECT)
    void testScopeKafkaConnectSetCorrectly(ExtensionContext extensionContext) {
        final TestStorage testStorage = storageMap.get(extensionContext);
        final String clusterName = testStorage.getClusterName();

        // SCOPE TESTING
        resourceManager.createResourceWithWait(extensionContext, KafkaConnectTemplates.kafkaConnect(clusterName, clusterName, 1)
            .editMetadata()
                .withNamespace(Environment.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withBootstrapServers(KafkaResources.bootstrapServiceName(oauthClusterName) + ":" + scopeListenerPort)
                .withConfig(connectorConfig)
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .withNewKafkaClientAuthenticationOAuth()
                    .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                    .withClientId("kafka-client")
                    .withNewClientSecret()
                        .withSecretName(OAUTH_KAFKA_CLIENT_SECRET)
                        .withKey(OAUTH_KEY)
                    .endClientSecret()
                    // scope set correctly regarding to the scope-test realm
                    .withScope("test")
                .endKafkaClientAuthenticationOAuth()
                .withTls(null)
                .endSpec()
            .build());

        // Kafka connect passed the validation process (implicit the KafkaConnect is up)
        // explicitly verifying also logs
        String kafkaConnectPodName = kubeClient().listPodsByPrefixInName(Environment.TEST_SUITE_NAMESPACE, KafkaResource.getKafkaPodName(oauthClusterName, 0)).get(0).getMetadata().getName();

        String kafkaLog = kubeClient().logsInSpecificNamespace(Environment.TEST_SUITE_NAMESPACE, kafkaConnectPodName);
        assertThat(kafkaLog, CoreMatchers.containsString("Access token expires at"));
        assertThat(kafkaLog, CoreMatchers.containsString("Evaluating path: $[*][?]"));
        assertThat(kafkaLog, CoreMatchers.containsString("Evaluating path: @['scope']"));
        assertThat(kafkaLog, CoreMatchers.containsString("User validated"));
        assertThat(kafkaLog, CoreMatchers.containsString("Set validated token on callback"));
    }

    @ParallelTest
    void testClientScopeKafkaSetCorrectly(ExtensionContext extensionContext) {
        final TestStorage testStorage = storageMap.get(extensionContext);
        final String clusterName = testStorage.getClusterName();
        final String producerName = OAUTH_PRODUCER_NAME + "-" + clusterName;
        final String consumerName = OAUTH_CONSUMER_NAME + "-" + clusterName;
        final String topicName = testStorage.getTopicName();

        KafkaClients oauthInternalClientChecksJob = new KafkaClientsBuilder()
            .withNamespaceName(Environment.TEST_SUITE_NAMESPACE)
            .withProducerName(producerName)
            .withConsumerName(consumerName)
            .withBootstrapAddress(KafkaResources.bootstrapServiceName(oauthClusterName) + ":" + scopeListenerPort)
            .withTopicName(topicName)
            .withMessageCount(MESSAGE_COUNT)
            // configures SASL/PLAIN to be used
            .withAdditionalConfig(additionalOauthConfig)
            .build();

        // clientScope is set to 'test' by default

        // verification phase the KafkaClient to authenticate.
        resourceManager.createResourceWithWait(extensionContext, KafkaTopicTemplates.topic(oauthClusterName, topicName, Environment.TEST_SUITE_NAMESPACE).build());

        resourceManager.createResourceWithWait(extensionContext, oauthInternalClientChecksJob.producerStrimzi());
        // client should succeeded because we set to `clientScope=test` and also Kafka has `scope=test`
        ClientUtils.waitForClientSuccess(producerName, Environment.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);
    }

    @IsolatedTest("Modification of shared Kafka cluster")
    void testClientScopeKafkaSetIncorrectly(ExtensionContext extensionContext) throws UnexpectedException {
        final TestStorage testStorage = storageMap.get(extensionContext);
        final String clusterName = testStorage.getClusterName();
        final String producerName = OAUTH_PRODUCER_NAME + "-" + clusterName;
        final String consumerName = OAUTH_CONSUMER_NAME + "-" + clusterName;
        final String topicName = testStorage.getTopicName();
        final LabelSelector kafkaSelector = KafkaResource.getLabelSelector(oauthClusterName, KafkaResources.kafkaStatefulSetName(oauthClusterName));

        KafkaClients oauthInternalClientChecksJob = new KafkaClientsBuilder()
            .withNamespaceName(Environment.TEST_SUITE_NAMESPACE)
            .withProducerName(producerName)
            .withConsumerName(consumerName)
            .withBootstrapAddress(KafkaResources.bootstrapServiceName(oauthClusterName) + ":" + scopeListenerPort)
            .withTopicName(topicName)
            .withMessageCount(MESSAGE_COUNT)
            // configures SASL/PLAIN to be used
            .withAdditionalConfig(additionalOauthConfig)
            .build();

        Map<String, String> kafkaPods = PodUtils.podSnapshot(Environment.TEST_SUITE_NAMESPACE, kafkaSelector);

        // re-configuring Kafka listener to have client scope assigned to null
        KafkaResource.replaceKafkaResourceInSpecificNamespace(oauthClusterName, kafka -> {
            List<GenericKafkaListener> scopeListeners = kafka.getSpec().getKafka().getListeners()
                .stream()
                .filter(listener -> listener.getName().equals(scopeListener))
                .collect(Collectors.toList());

            ((KafkaListenerAuthenticationOAuth) scopeListeners.get(0).getAuth()).setClientScope(null);
            kafka.getSpec().getKafka().getListeners().set(0, scopeListeners.get(0));
        }, Environment.TEST_SUITE_NAMESPACE);

        kafkaPods = RollingUpdateUtils.waitTillComponentHasRolledAndPodsReady(Environment.TEST_SUITE_NAMESPACE, kafkaSelector, 3, kafkaPods);

        // verification phase client should fail here because clientScope is set to 'null'
        resourceManager.createResourceWithWait(extensionContext, KafkaTopicTemplates.topic(oauthClusterName, topicName, Environment.TEST_SUITE_NAMESPACE).build());

        resourceManager.createResourceWithWait(extensionContext, oauthInternalClientChecksJob.producerStrimzi());
        // client should fail because the listener requires scope: 'test' in JWT token but was (the listener) temporarily
        // configured without clientScope resulting in a JWT token without the scope claim when using the clientId and
        // secret passed via SASL/PLAIN to obtain an access token in client's name.
        ClientUtils.waitForClientTimeout(producerName, Environment.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);
        JobUtils.deleteJobWithWait(Environment.TEST_SUITE_NAMESPACE, producerName);

        // rollback previous configuration
        // re-configuring Kafka listener to have client scope assigned to 'test'
        KafkaResource.replaceKafkaResourceInSpecificNamespace(oauthClusterName, kafka -> {
            List<GenericKafkaListener> scopeListeners = kafka.getSpec().getKafka().getListeners()
                .stream()
                .filter(listener -> listener.getName().equals(scopeListener))
                .collect(Collectors.toList());

            ((KafkaListenerAuthenticationOAuth) scopeListeners.get(0).getAuth()).setClientScope("test");
            kafka.getSpec().getKafka().getListeners().set(0, scopeListeners.get(0));
        }, Environment.TEST_SUITE_NAMESPACE);

        RollingUpdateUtils.waitTillComponentHasRolledAndPodsReady(Environment.TEST_SUITE_NAMESPACE, kafkaSelector, 3, kafkaPods);
    }

    @BeforeAll
    void setUp(ExtensionContext extensionContext) {
        super.setupCoAndKeycloak(extensionContext, Environment.TEST_SUITE_NAMESPACE);

        keycloakInstance.setRealm("scope-test", false);

        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaPersistent(oauthClusterName, 3)
            .editMetadata()
                .withNamespace(Environment.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editSpec()
                .editKafka()
                .withListeners(
                    new GenericKafkaListenerBuilder()
                        .withName(scopeListener)
                        .withPort(Integer.parseInt(scopeListenerPort))
                        .withType(KafkaListenerType.INTERNAL)
                        .withTls(false)
                        .withNewKafkaListenerAuthenticationOAuth()
                            .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                            .withJwksExpirySeconds(keycloakInstance.getJwksExpireSeconds())
                            .withJwksRefreshSeconds(keycloakInstance.getJwksRefreshSeconds())
                            .withJwksEndpointUri(keycloakInstance.getJwksEndpointUri())
                            .withUserNameClaim(keycloakInstance.getUserNameClaim())
                            .withEnablePlain(true)
                            .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                            .withCheckAudience(false)
                            .withCustomClaimCheck("@.scope =~ /.*test.*/")
                            .withClientScope("test")
                            .withClientId("kafka-component")
                        .endKafkaListenerAuthenticationOAuth()
                    .build())
                .endKafka()
            .endSpec()
            .build());
    }
}
