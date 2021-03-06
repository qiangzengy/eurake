/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.Date;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.aws.AwsBinder;
import com.netflix.eureka.aws.AwsBinderDelegate;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.AwsInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.EurekaMonitors;
import com.thoughtworks.xstream.XStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that kick starts the eureka server.
 *
 * <p>
 * The eureka server is configured by using the configuration
 * {@link EurekaServerConfig} specified by <em>eureka.server.props</em> in the
 * classpath.  The eureka client component is also initialized by using the
 * configuration {@link EurekaInstanceConfig} specified by
 * <em>eureka.client.props</em>. If the server runs in the AWS cloud, the eureka
 * server binds it to the elastic ip as specified.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim, David Liu
 *
 */
public class EurekaBootStrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(EurekaBootStrap.class);

    private static final String TEST = "test";

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";

    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    private static final String CLOUD = "cloud";
    private static final String DEFAULT = "default";

    private static final String ARCHAIUS_DEPLOYMENT_DATACENTER = "archaius.deployment.datacenter";

    private static final String EUREKA_DATACENTER = "eureka.datacenter";

    protected volatile EurekaServerContext serverContext;
    protected volatile AwsBinder awsBinder;
    
    private EurekaClient eurekaClient;

    /**
     * Construct a default instance of Eureka boostrap
     */
    public EurekaBootStrap() {
        this(null);
    }
    
    /**
     * Construct an instance of eureka bootstrap with the supplied eureka client
     * 
     * @param eurekaClient the eureka client to bootstrap
     */
    public EurekaBootStrap(EurekaClient eurekaClient) {
        this.eurekaClient = eurekaClient;
    }

    /**
     * Initializes Eureka, including syncing up with other Eureka peers and publishing the registry.
     *
     * @see
     * javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
     */

    // 当Servlet 容器启动Web 应用时调用该方法。在调用完该方法之后，
    // 容器再对Filter初始化，并且对那些在Web 应用启动时就需要被初始化的Servlet 进行初始化。
    // 系统初始化监听器，EurekaServer启动的入口
    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {

            /*
            对EurekaBootStrap分析，EurekaBootStrap实现了ServletContextListener。
            在EurekaServer启动时，会先执行contextInitialized()方法，在这里面会初始化环境(initEurekaEnvironment())
            和初始化EurekaServer上下文(初始化EurekaServer上下文());
            initEurekaEnvironment()这个方法，里面有这个ConfigurationManager.getConfigInstance()，
            采用了单例模式，获取ConfigurationManager实例。创建一个ConcurrentCompositeConfiguration这个类，
            这个类就是代表所谓的配置，包括了eureka需要的所有的配置。
            在初始化ConcurrentCompositeConfiguration这个实例时，里面有一个clear(),
            clear()里面会发布 一个事件（EVENT_CLEAR），
            就是往ConcurrentCompositeConfiguration实例加入了一堆别的config，
            然后搞完了以后，就直接返回了这个实例，就是作为所谓的那个配置的单例。
            初始化数据中心的配置，如果没有配置的话，就是DEFAULT data center，
            初始化eureka运行的环境，如果你没有配置的话，默认就给你设置为test环境。
             */

            // 初始化环境
            initEurekaEnvironment();

            // 初始化EurekaServer上下文
            initEurekaServerContext();

            ServletContext sc = event.getServletContext();
            sc.setAttribute(EurekaServerContext.class.getName(), serverContext);
        } catch (Throwable e) {
            logger.error("Cannot bootstrap eureka server :", e);
            throw new RuntimeException("Cannot bootstrap eureka server :", e);
        }
    }

    /**
     * Users can override to initialize the environment themselves.
     */
    protected void initEurekaEnvironment() throws Exception {
        logger.info("Setting the eureka configuration..");

        // 配置管理器，获取ConfigurationManager实例，单例模式，管理eureka自己所有的配置的
        // getConfigInstance()用了 static volatile AbstractConfiguration instance = null;volatile关键字，保证线程可见性

        /*
         * ConfigurationManager过程分析：
         * 1.创建一个ConcurrentCompositeConfiguration实例，它其实就代表了所有的配置，包括eureka需要的所有配置，它里面会调用clear()，
         * fireEvent()发布了一个事件（EVENT_CLEAR），fireEvent()这个方法其实是父类的方法，牵扯比较复杂的另外一个项目
         * （ConfigurationManager本身不是属于eureka的源码，是属于netflix config项目的源码）。
         * 2.就是往ConcurrentCompositeConfiguration实例加入一堆别的config，然后返回这个实例，就是作为所谓的那个配置的单例
         * 3.初始化数据中心的配置，如果没有配置，就是DEFAULT datacenter
         * 4.初始化eureka运行的环境，如果没有配置的话，默认为test环境
         */
        String dataCenter = ConfigurationManager.getConfigInstance().getString(EUREKA_DATACENTER);

        if (dataCenter == null) {
            logger.info("Eureka data center value eureka.datacenter is not set, defaulting to default");
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, DEFAULT);
        } else {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, dataCenter);
        }
        String environment = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT);
        if (environment == null) {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, TEST);
            logger.info("Eureka environment value eureka.environment is not set, defaulting to test");
        }
    }

    /**
     * init hook for server context. Override for custom logic.
     */
    protected void initEurekaServerContext() throws Exception {

        // 1.加载eureka-server.properties文件中的配置，EurekaServerConfig是一个接口

        /*
        加载eureka-server.properties的过程：
        （1）创建了一个DefaultEurekaServerConfig对象
        （2）创建DefaultEurekaServerConfig对象的时候，在里面会有一个init方法
        （3）先是将eureka-server.properties中的配置加载到了一个Properties对象中，然后将Properties对象中的配置放到ConfigurationManager中去，此时ConfigurationManager中去就有了所有的配置了
        （4）然后DefaultEurekaServerConfig提供的获取配置项的各个方法，都是通过硬编码的配置项名称，从DynamicPropertyFactory中获取配置项的值，DynamicPropertyFactory是从ConfigurationManager那儿来的，所以也包含了所有配置项的值
        （5）在获取配置项的时候，如果没有配置，那么就会有默认的值，全部属性都是有默认值的
         */

        // 读取EurekaServer默认配置
        EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig();

        // For backward compatibility
        JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);
        XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);

        logger.info("Initializing the eureka client...");
        logger.info(eurekaServerConfig.getJsonCodecName());
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);
        // 2.初始化一个ApplicationInfoManager，包含了服务实例的信息、配置，作为服务实例管理的一个组件
        ApplicationInfoManager applicationInfoManager = null;


        if (eurekaClient == null) {
            // 是否在云环境上
            EurekaInstanceConfig instanceConfig = isCloud(ConfigurationManager.getDeploymentContext())
                    ? new CloudInstanceConfig()
                    // 就是将eureka-client.properties文件中配置加载到ConfigurationManager中去，
                    // 然后基于EurekaInstanceConfig对外暴露的接口来获取这个
                    // eureka-client.properties文件中的一些配置项的读取，而且人家提供了所有配置项的默认值
                    : new MyDataCenterInstanceConfig();

            applicationInfoManager = new ApplicationInfoManager(
                    instanceConfig, new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());
            // 3.初始化EurekaServer内部的一个EurekaClient(用来跟其他server节点进行注册和通信)
            EurekaClientConfig eurekaClientConfig = new DefaultEurekaClientConfig();
            // 构建一个EurekaClient实例
            eurekaClient = new DiscoveryClient(applicationInfoManager, eurekaClientConfig);
        } else {
            applicationInfoManager = eurekaClient.getApplicationInfoManager();
        }

        // 感知实例注册表
        PeerAwareInstanceRegistry registry;
        if (isAws(applicationInfoManager.getInfo())) {
            registry = new AwsInstanceRegistry(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
            awsBinder = new AwsBinderDelegate(eurekaServerConfig, eurekaClient.getEurekaClientConfig(), registry, applicationInfoManager);
            awsBinder.start();
        } else {
            registry = new PeerAwareInstanceRegistryImpl(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
        }

        // Eureka集群
        PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClient.getEurekaClientConfig(),
                serverCodecs,
                applicationInfoManager
        );

        serverContext = new DefaultEurekaServerContext(
                eurekaServerConfig,
                serverCodecs,
                registry,
                peerEurekaNodes,
                applicationInfoManager
        );

        EurekaServerContextHolder.initialize(serverContext);

        serverContext.initialize();
        logger.info("Initialized server context");

        // Copy registry from neighboring eureka node
        // 从相邻的eureka节点复制注册表
        int registryCount = registry.syncUp();

        // 自动感知故障摘除
        registry.openForTraffic(applicationInfoManager, registryCount);

        // Register all monitoring statistics.
        // 注册所有监视统计信息
        EurekaMonitors.registerAllStats();
    }
    
    protected PeerEurekaNodes getPeerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig eurekaServerConfig, EurekaClientConfig eurekaClientConfig, ServerCodecs serverCodecs, ApplicationInfoManager applicationInfoManager) {
        PeerEurekaNodes peerEurekaNodes = new PeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClientConfig,
                serverCodecs,
                applicationInfoManager
        );
        
        return peerEurekaNodes;
    }

    /**
     * Handles Eureka cleanup, including shutting down all monitors and yielding all EIPs.
     *
     * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
     */

    // 当Servlet 容器终止Web 应用时调用该方法。在调用该方法之前，容器会先销毁所有的Servlet 和Filter 过滤器。
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        try {
            logger.info("{} Shutting down Eureka Server..", new Date().toString());
            ServletContext sc = event.getServletContext();
            sc.removeAttribute(EurekaServerContext.class.getName());
            destroyEurekaServerContext();
            destroyEurekaEnvironment();

        } catch (Throwable e) {
            logger.error("Error shutting down eureka", e);
        }
        logger.info("{} Eureka Service is now shutdown...", new Date().toString());
    }

    /**
     * Server context shutdown hook. Override for custom logic
     */
    protected void destroyEurekaServerContext() throws Exception {
        EurekaMonitors.shutdown();
        if (awsBinder != null) {
            awsBinder.shutdown();
        }
        if (serverContext != null) {
            serverContext.shutdown();
        }
    }

    /**
     * Users can override to clean up the environment themselves.
     */
    protected void destroyEurekaEnvironment() throws Exception {

    }

    protected boolean isAws(InstanceInfo selfInstanceInfo) {
        boolean result = DataCenterInfo.Name.Amazon == selfInstanceInfo.getDataCenterInfo().getName();
        logger.info("isAws returned {}", result);
        return result;
    }

    protected boolean isCloud(DeploymentContext deploymentContext) {
        logger.info("Deployment datacenter is {}", deploymentContext.getDeploymentDatacenter());
        return CLOUD.equals(deploymentContext.getDeploymentDatacenter());
    }
}
