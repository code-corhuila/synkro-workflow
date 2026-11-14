package co.edu.corhuila.synkro.workflow.adapter.out.persistence;

import java.util.UUID;

/** Pure derivation of the two-int PostgreSQL advisory key owned by Workflow; the adapter is the only user. */
final class AdvisoryKeys {
    /** Workflow namespace "WFLK"; Auth refresh keeps 0x52544F4B, so the first key never aliases it. */
    static final int FIRST=0x57464C4B;
    private AdvisoryKeys(){}
    /** XOR of the four signed 32-bit UUID words. A collision only serializes sagas; identity is the saga_id row. */
    static int second(UUID id) {
        long m=id.getMostSignificantBits(),l=id.getLeastSignificantBits();
        return (int)(m>>>32)^(int)m^(int)(l>>>32)^(int)l;
    }
}
