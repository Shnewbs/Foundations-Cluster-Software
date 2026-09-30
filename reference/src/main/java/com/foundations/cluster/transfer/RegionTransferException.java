package com.foundations.cluster.transfer;

public final class RegionTransferException extends RuntimeException {
    public RegionTransferException(String message) {
        super(message);
    }

    public RegionTransferException(String message, Throwable cause) {
        super(message, cause);
    }
}
