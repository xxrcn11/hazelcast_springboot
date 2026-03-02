package com.bt.hz.domain.sessions.models;

import com.bt.hz.config.ServerDataSerializableFactory;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.io.IOException;

/**
 * Spring @Component 절대 사용 금지.
 * 순수 POJO 형태로 작성하며, Hazelcast 클러스터 네트워크 I/O 성능 극대화를 위해
 * 표준 Java 직렬화(Serializable) 대신 IdentifiedDataSerializable을 구현합니다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor // 역직렬화를 위해 기본 생성자 필수
@AllArgsConstructor
public class BCB001I implements IdentifiedDataSerializable {

    private String id;
    private String name;
    private int age;

    @Override
    public int getFactoryId() {
        return ServerDataSerializableFactory.FACTORY_ID;
    }

    @Override
    public int getClassId() {
        return ServerDataSerializableFactory.BCB001I_TYPE;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeString(id);
        out.writeString(name);
        out.writeInt(age);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        id = in.readString();
        name = in.readString();
        age = in.readInt();
    }
}
