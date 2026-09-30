package com.foundations.cluster.transfer;

@FunctionalInterface
public interface TransferLogger {
    void log(String line);

    static TransferLogger stdout() {
        return System.out::println;
    }
}
