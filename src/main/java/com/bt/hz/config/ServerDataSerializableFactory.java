package com.bt.hz.config;

import com.bt.hz.domain.sessions.models.BCB001I;
import com.bt.hz.domain.sessions.models.SessionDto;
import com.hazelcast.nio.serialization.DataSerializableFactory;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;

public class ServerDataSerializableFactory implements DataSerializableFactory {
    public static final int FACTORY_ID = 1001;

    public static final int BCB001I_TYPE = 1;
    public static final int SESSION_DTO_TYPE = 2;

    @Override
    public IdentifiedDataSerializable create(int typeId) {
        switch (typeId) {
            case BCB001I_TYPE:
                return new BCB001I();
            case SESSION_DTO_TYPE:
                return new SessionDto();
            default:
                return null; // For unknown type IDs
        }
    }
}
