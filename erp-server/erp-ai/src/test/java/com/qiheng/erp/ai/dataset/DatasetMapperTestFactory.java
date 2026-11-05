package com.qiheng.erp.ai.dataset;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.mapper.AiDatasetMapper;
import org.mybatis.spring.SqlSessionTemplate;
import javax.sql.DataSource;

/** 隔离数据库测试使用真实 MyBatis-Plus Mapper。 */
final class DatasetMapperTestFactory {
    static MybatisDatasetRepository create(DataSource source, ObjectMapper json) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AiDatasetMapper.class);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(bean.getObject());
        return new MybatisDatasetRepository(session.getMapper(AiDatasetMapper.class), json);
    }
}
