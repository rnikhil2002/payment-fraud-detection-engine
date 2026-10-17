package com.nikhilr.fraud.model;

/**
 * Facts pulled from the Neo4j graph for one payment. These feed both the rule engine
 * and the ML model.
 *
 * @param accountsOnDevice       distinct accounts that have used this device
 * @param devicesForAccount24h   distinct devices this account used in the last 24h
 * @param txLast10m              payments sent by this account in the last 10 minutes
 * @param deviceRingSize         other accounts reachable through shared devices
 * @param flaggedNeighbors       accounts sharing a device with this one that were already blocked
 * @param muleChainDepth         longest chain of fast transfers that ends at the sender (last hour)
 * @param inboundLastHour        money the sender received by transfer in the last hour
 * @param merchantFlaggedPayers  blocked accounts that paid this merchant
 * @param newDevice              first time this account was seen on this device
 */
public record GraphSignals(
        int accountsOnDevice,
        int devicesForAccount24h,
        int txLast10m,
        int deviceRingSize,
        int flaggedNeighbors,
        int muleChainDepth,
        double inboundLastHour,
        int merchantFlaggedPayers,
        boolean newDevice) {

    public static GraphSignals empty() {
        return new GraphSignals(1, 1, 1, 0, 0, 0, 0.0, 0, false);
    }

    /** Share of recently received money that is being pushed straight back out. */
    public double passThroughRatio(double outgoingAmount) {
        if (inboundLastHour <= 0) {
            return 0.0;
        }
        return Math.min(1.0, outgoingAmount / inboundLastHour);
    }
}
