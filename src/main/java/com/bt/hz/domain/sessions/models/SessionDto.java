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
@NoArgsConstructor // 역직렬화를 위해 기본 생성자 필수
@AllArgsConstructor
public class SessionDto implements IdentifiedDataSerializable, java.io.Serializable {

    private String userId;
    private String userName;
    private String role; // 단순화를 위해 단일 롤 (ADMIN/USER 등)
    private long num;
    private int age;
    private String loginAt;

    @Override
    public int getFactoryId() {
        return ServerDataSerializableFactory.FACTORY_ID;
    }

    @Override
    public int getClassId() {
        return ServerDataSerializableFactory.SESSION_DTO_TYPE;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeString(userId);
        out.writeString(userName);
        out.writeString(role);
        out.writeLong(num);
        out.writeInt(age);
        out.writeString(loginAt);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        userId = in.readString();
        userName = in.readString();
        role = in.readString();
        num = in.readLong();
        age = in.readInt();
        loginAt = in.readString();
    }

}
