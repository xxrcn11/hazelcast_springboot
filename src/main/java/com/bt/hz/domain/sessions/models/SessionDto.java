package com.bt.hz.domain.sessions.models;

import java.io.IOException;
import java.time.LocalDateTime;

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
public class SessionDto implements IdentifiedDataSerializable {

    private String userId;
    private String username;
    private String role; // 단순화를 위해 단일 롤 (ADMIN/USER 등)
    private LocalDateTime loginAt;

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
        out.writeString(username);
        out.writeString(role);
        out.writeLong(loginAt.toEpochSecond(java.time.ZoneOffset.UTC));
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        userId = in.readString();
        username = in.readString();
        role = in.readString();
        loginAt = LocalDateTime.ofEpochSecond(in.readLong(), 0, java.time.ZoneOffset.UTC);
    }

}
