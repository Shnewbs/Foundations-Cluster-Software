package com.foundations.cluster.manager;

final class LayerAssignment {
    final String sessionId;
    final String playerId;
    volatile String partyId;
    volatile String layerId;
    volatile String workerId;
    volatile LayerCellKey cell;
    volatile long updatedAtMillis = System.currentTimeMillis();

    LayerAssignment(String sessionId, String playerId, String partyId, String layerId, String workerId, LayerCellKey cell) {
        this.sessionId = sessionId;
        this.playerId = playerId;
        this.partyId = partyId == null ? "" : partyId;
        this.layerId = layerId;
        this.workerId = workerId;
        this.cell = cell;
    }
}
