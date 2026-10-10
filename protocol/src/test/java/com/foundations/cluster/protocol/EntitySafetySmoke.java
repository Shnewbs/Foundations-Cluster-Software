package com.foundations.cluster.protocol;
/** Repeatable Java 21 standalone test entrypoint; reference protocols only. */
public final class EntitySafetySmoke {
    private EntitySafetySmoke() {}
    public static void main(String[] args) throws Exception {
        EntityOwnershipLedgerSelfTest.main(new String[0]);
        PickupTransferLedgerSelfTest.main(new String[0]);
        System.out.println("Entity safety reference smoke: PASS (not Minecraft workers or live pickup)");
    }
}
