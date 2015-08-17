package pl.allegro.tech.hermes.consumers.consumer.receiver.kafka;

import com.codahale.metrics.Timer;
import com.google.common.collect.ImmutableMap;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.ConsumerTimeoutException;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;
import pl.allegro.tech.hermes.api.Topic;
import pl.allegro.tech.hermes.common.config.ConfigFactory;
import pl.allegro.tech.hermes.common.config.Configs;
import pl.allegro.tech.hermes.common.exception.InternalProcessingException;
import pl.allegro.tech.hermes.common.message.wrapper.MessageContentWrapper;
import pl.allegro.tech.hermes.common.message.wrapper.UnwrappedMessageContent;
import pl.allegro.tech.hermes.common.time.Clock;
import pl.allegro.tech.hermes.consumers.consumer.Message;
import pl.allegro.tech.hermes.consumers.consumer.receiver.MessageReceiver;
import pl.allegro.tech.hermes.consumers.consumer.receiver.MessageReceivingTimeoutException;

import java.util.List;
import java.util.Map;

public class KafkaMessageReceiver implements MessageReceiver {
    private final ConsumerIterator<byte[], byte[]> iterator;
    private final Topic topic;
    private final ConsumerConnector consumerConnector;
    private final MessageContentWrapper contentWrapper;
    private final Timer readingTimer;
    private final Clock clock;

    public KafkaMessageReceiver(Topic topic, ConsumerConnector consumerConnector, ConfigFactory configFactory,
                                MessageContentWrapper contentWrapper, Timer readingTimer, Clock clock) {
        this.topic = topic;
        this.consumerConnector = consumerConnector;
        this.contentWrapper = contentWrapper;
        this.readingTimer = readingTimer;
        this.clock = clock;

        Map<String, Integer> topicCountMap = ImmutableMap.of(
                topic.getQualifiedName(), configFactory.getIntProperty(Configs.KAFKA_STREAM_COUNT)
        );
        Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumerConnector.createMessageStreams(
                topicCountMap
        );
        KafkaStream<byte[], byte[]> stream = consumerMap.get(topic.getQualifiedName()).get(0);
        iterator = stream.iterator();
    }

    @Override
    public Message next() {
        try (Timer.Context readingTimerContext = readingTimer.time()) {
            MessageAndMetadata<byte[], byte[]> message = iterator.next();
            UnwrappedMessageContent unwrappedContent = contentWrapper.unwrap(message.message(), topic);

            return new Message(
                    unwrappedContent.getMessageMetadata().getId(),
                    message.offset(),
                    message.partition(),
                    message.topic(),
                    unwrappedContent.getContent(),
                    unwrappedContent.getMessageMetadata().getTimestamp(),
                    clock.getTime());

        } catch (ConsumerTimeoutException consumerTimeoutException) {
            throw new MessageReceivingTimeoutException("No messages received", consumerTimeoutException);
        } catch (Exception e) {
            throw new InternalProcessingException("Message received failed", e);
        }
    }

    @Override
    public void stop() {
        consumerConnector.shutdown();
    }

}
