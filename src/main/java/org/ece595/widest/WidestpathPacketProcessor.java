package org.ece595.widest;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.IpPrefix;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.HostId;
import org.onosproject.net.Path;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.PathIntent;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


public class WidestpathPacketProcessor implements PacketProcessor {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private RealtimeWidestRouting widestPathRouting; //strategy pattern
    private ApplicationId appId;
    private ConcurrentMap<Set<Criterion>, Intent> intentMap;
    private IntentService intentService;

    public WidestpathPacketProcessor(ApplicationId appId,
                                     ConcurrentMap<Set<Criterion>, Intent> intentMap,
                                     RealtimeWidestRouting routing,
                                     IntentService intentService) {
        this.widestPathRouting = routing;
        this.appId = appId;
        this.intentMap = intentMap;
        this.intentService = intentService;
    }

    @Override
    public void process(PacketContext context) {

        if (context.isHandled()) {
            return;
        }

        Ethernet pkt = context.inPacket().parsed();
        if (pkt.getEtherType() == Ethernet.TYPE_IPV4) {

            HostId srcHostId = HostId.hostId(pkt.getSourceMAC());  //HostId is VlanID+MAC
            HostId dstHostId = HostId.hostId(pkt.getDestinationMAC());

            Set<Path> paths = widestPathRouting.getPaths(srcHostId, dstHostId);//in fact one path is returned

            if (paths.isEmpty()) {
                log.warn("paths is Empty !!! no Path is available");
                context.block();
                return;
            }

            IPv4 ipPkt = (IPv4) pkt.getPayload();  //peel off MAC info, get ip layer packet
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_IPV4)
                    .matchIPSrc(IpPrefix.valueOf(ipPkt.getSourceAddress(), 32))
                    .matchIPDst(IpPrefix.valueOf(ipPkt.getDestinationAddress(), 32))
                    .build();

            boolean isContain;
            isContain = intentMap.containsKey(selector.criteria());
            if (isContain) {
                context.block();
                return;
            }


            Path result = paths.iterator().next();
            log.info("\n------ Path Info ------\nSrc:{}, Dst:{}\n{}",
                     IpPrefix.valueOf(ipPkt.getSourceAddress(), 32).toString(),
                     IpPrefix.valueOf(ipPkt.getDestinationAddress(), 32),
                     result.links().toString().replace("Default", "\n"));

            PathIntent pathIntent = PathIntent.builder()
                    .path(result)
                    .appId(appId)
                    .priority(65432)
                    .selector(selector)
                    .treatment(DefaultTrafficTreatment.emptyTreatment())
                    .build();

            intentService.submit(pathIntent);

            intentMap.put(selector.criteria(), pathIntent);

            context.block();
        }
    }
}