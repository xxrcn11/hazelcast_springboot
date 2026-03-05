package com.bt.hz.domain.sessions.models;

import java.io.IOException;

import com.bt.hz.config.ServerDataSerializableFactory;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SYSSE015I implements IdentifiedDataSerializable {

    private String stdYmd;
    private String stdHour;
    private int cnt;

    @Override
    public int getFactoryId() {
        return ServerDataSerializableFactory.FACTORY_ID;
    }

    @Override
    public int getClassId() {
        return ServerDataSerializableFactory.SYSSE015I_TYPE;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeString(stdYmd);
        out.writeString(stdHour);
        out.writeInt(cnt);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        stdYmd = in.readString();
        stdHour = in.readString();
        cnt = in.readInt();
    }
}
