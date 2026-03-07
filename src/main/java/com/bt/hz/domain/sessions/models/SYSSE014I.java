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
public class SYSSE014I implements IdentifiedDataSerializable {

    private String userId;
    private String userName;
    private String role;
    private String loginType;
    private long num;
    private int age;
    private String loginAt;
    private String logoutAt;

    @Override
    public int getFactoryId() {
        return ServerDataSerializableFactory.FACTORY_ID;
    }

    @Override
    public int getClassId() {
        return ServerDataSerializableFactory.SYSSE014I_TYPE;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeString(userId);
        out.writeString(userName);
        out.writeString(role);
        out.writeString(loginType);
        out.writeLong(num);
        out.writeInt(age);
        out.writeString(loginAt);
        out.writeString(logoutAt);

    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        userId = in.readString();
        userName = in.readString();
        role = in.readString();
        loginType = in.readString();
        num = in.readLong();
        age = in.readInt();
        loginAt = in.readString();
        logoutAt = in.readString();

    }
}
