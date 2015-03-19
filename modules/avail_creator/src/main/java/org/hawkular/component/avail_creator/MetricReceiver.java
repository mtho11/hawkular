/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.component.avail_creator;

import com.google.gson.GsonBuilder;
import org.hawkular.bus.common.BasicMessage;
import org.hawkular.bus.common.ConnectionContextFactory;
import org.hawkular.bus.common.Endpoint;
import org.hawkular.bus.common.MessageProcessor;
import org.hawkular.bus.common.ObjectMessage;
import org.hawkular.bus.common.producer.ProducerConnectionContext;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.jms.ConnectionFactory;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Receiver that listens on JMS Topic and checks for metrics *.status.code
 * Listening goes on 'java:/topic/HawkularMetricData'.
 * Then computes availability and forwards that to a topic for availability
 *
 * Requires this in standalone.xml:
 *
 *  <admin-object use-java-context="true"
 *      enabled="true"
 *      class-name="org.apache.activemq.command.ActiveMQTopic"
 *      jndi-name="java:/topic/HawkularAvailData"
 *      pool-name="HawkularAvailData">
 *            <config-property name="PhysicalName">HawkularAvailData</config-property>
 *  </admin-object>
 *
 * @author Heiko W. Rupp
 */
@MessageDriven( activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
        @ActivationConfigProperty(propertyName = "destination", propertyValue = "HawkularMetricData")
})
@SuppressWarnings("unused")
public class MetricReceiver implements MessageListener {

    @javax.annotation.Resource ( lookup = "java:/topic/HawkularAvailData")
    javax.jms.Topic topic;

    @javax.annotation.Resource (lookup = "java:/HawkularBusConnectionFactory")
    ConnectionFactory connectionFactory;


    @Override
    public void onMessage(Message message) {

        try {

            String payload = ((TextMessage)message).getText();
            Map map = new GsonBuilder().create().fromJson(payload, Map.class);

            Map metricDataMap = (Map) map.get("metricData");
            // Get <rid>.status.code  metrics
            String tenant = (String) metricDataMap.get("tenantId");
            List<Map<String,Object>> inputList = (List<Map<String, Object>>) metricDataMap.get("data");
            List<AvailRecord> outer = new ArrayList<>();

            for (Map<String,Object> item: inputList) {
                String source = (String) item.get("source");
                if (source.endsWith(".status.code")) {
                    double codeD = (double) item.get("value");
                    int code = (int) codeD;

                    String id = source.substring(0,source.indexOf("."));
                    double timestampD = (double) item.get("timestamp");
                    long timestamp = (long) timestampD;

                    String avail = computeAvail(code);

                    AvailRecord ar = new AvailRecord(tenant, id,timestamp,avail);
                    outer.add(ar);
                }
            }


            if (topic != null) {

                try (ConnectionContextFactory factory = new ConnectionContextFactory(connectionFactory)) {
                    Endpoint endpoint = new Endpoint(Endpoint.Type.TOPIC, topic.getTopicName());
                    ProducerConnectionContext pc = factory.createProducerConnectionContext(endpoint);
                    BasicMessage msg = new ObjectMessage(outer);
                    MessageProcessor processor = new MessageProcessor();
                    processor.send(pc, msg);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            else {
                Log.LOG.wNoTopicConnection("HawkularAvailData");
            }

        } catch (Exception e) {
            e.printStackTrace();  // TODO: Customise this generated block
        }

    }

    /**
     * Do the work of computing the availability from the status code
     * @param code Status code of the web request
     * @return "UP" or "DOWN" accordingly
     */
    private String computeAvail(int code) {
        if (code <= 399) {
            return "UP";
        }
        return "DOWN";
    }


    private static class AvailRecord {


        private String tenantId;
        private final String id;
        private final long timestamp;
        private final String avail;

        public AvailRecord(String tenantId, String id, long timestamp, String avail) {
            this.tenantId = tenantId;
            this.id = id;
            this.timestamp = timestamp;
            this.avail = avail;
        }
    }


}
