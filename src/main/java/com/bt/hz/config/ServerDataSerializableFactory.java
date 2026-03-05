package com.bt.hz.config;

import com.bt.hz.domain.sessions.models.BCB001I;
import com.bt.hz.domain.sessions.models.SYSSE014I;
import com.bt.hz.domain.sessions.models.SYSSE015I;
import com.bt.hz.domain.sessions.models.SessionDto;
import com.hazelcast.nio.serialization.DataSerializableFactory;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;

public class ServerDataSerializableFactory implements DataSerializableFactory {
    public static final int FACTORY_ID = 1001;

    public static final int BCB001I_TYPE = 1;
    public static final int SESSION_DTO_TYPE = 2;
    public static final int SYSSE014I_TYPE = 3;
    public static final int SYSSE015I_TYPE = 4;

    @Override
    public IdentifiedDataSerializable create(int typeId) {
        switch (typeId) {
            case BCB001I_TYPE:
                return new BCB001I();
            case SESSION_DTO_TYPE:
                return new SessionDto();
            case SYSSE014I_TYPE:
                return new SYSSE014I();
            case SYSSE015I_TYPE:
                return new SYSSE015I();
            default:
                return null; // For unknown type IDs
        }
    }
}
