package com.bt.hz.domain.sessions.mapper;

import com.bt.hz.domain.sessions.models.BCB001I;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * BCB001I 엔티티의 MyBatis 매퍼
 */
@Mapper
public interface BCB001IMapper {
    
    void insertOrUpdate(BCB001I bcb001i);
    
    void deleteById(@Param("id") String id);
    
    void deleteAll(@Param("keys") Collection<String> keys);
    
    BCB001I findById(@Param("id") String id);
    
    List<BCB001I> findAllByIds(@Param("keys") Collection<String> keys);
    
    List<String> findAllKeys();
}
