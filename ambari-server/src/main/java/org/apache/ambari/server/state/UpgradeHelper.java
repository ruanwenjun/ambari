/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.state;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ambari.annotations.Experimental;
import org.apache.ambari.annotations.ExperimentalFeature;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.internal.TaskResourceProvider;
import org.apache.ambari.server.controller.predicate.AndPredicate;
import org.apache.ambari.server.controller.spi.ClusterController;
import org.apache.ambari.server.controller.spi.NoSuchParentResourceException;
import org.apache.ambari.server.controller.spi.NoSuchResourceException;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.QueryResponse;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.controller.spi.UnsupportedPropertyException;
import org.apache.ambari.server.controller.utilities.ClusterControllerHelper;
import org.apache.ambari.server.controller.utilities.PredicateBuilder;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.events.listeners.upgrade.StackVersionListener;
import org.apache.ambari.server.orm.dao.RepositoryVersionDAO;
import org.apache.ambari.server.orm.dao.ServiceConfigDAO;
import org.apache.ambari.server.orm.entities.ClusterConfigEntity;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.orm.entities.ServiceConfigEntity;
import org.apache.ambari.server.stack.HostsType;
import org.apache.ambari.server.stack.MasterHostResolver;
import org.apache.ambari.server.state.stack.UpgradePack;
import org.apache.ambari.server.state.stack.UpgradePack.ProcessingComponent;
import org.apache.ambari.server.state.stack.upgrade.Direction;
import org.apache.ambari.server.state.stack.upgrade.Grouping;
import org.apache.ambari.server.state.stack.upgrade.ManualTask;
import org.apache.ambari.server.state.stack.upgrade.RestartTask;
import org.apache.ambari.server.state.stack.upgrade.StageWrapper;
import org.apache.ambari.server.state.stack.upgrade.StageWrapperBuilder;
import org.apache.ambari.server.state.stack.upgrade.StartTask;
import org.apache.ambari.server.state.stack.upgrade.StopTask;
import org.apache.ambari.server.state.stack.upgrade.Task;
import org.apache.ambari.server.state.stack.upgrade.Task.Type;
import org.apache.ambari.server.state.stack.upgrade.TaskWrapper;
import org.apache.ambari.server.state.stack.upgrade.UpgradeFunction;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

/**
 * Class to assist with upgrading a cluster.
 */
@Singleton
public class UpgradeHelper {

  private static final Logger LOG = LoggerFactory.getLogger(UpgradeHelper.class);

  /**
   * Matches on placeholder values such as
   *
   * <pre>
   * {{host}}
   * </pre>
   * and
   * <pre>
   * {{hdfs-site/foo}}
   * </pre>
   */
  private static final Pattern PLACEHOLDER_REGEX = Pattern.compile("(\\{\\{.*?\\}\\})");

  /**
   * Enum used to define placeholder text for replacement
   */
  private enum Placeholder {
    /**
     * No placeholder defined
     */
    OTHER(""),
    /**
     * A placeholder token that represents all of the hosts that a component is
     * deployed on. This can be used for cases where text needs to be rendered
     * with all of the hosts mentioned by their FQDN.
     */
    HOST_ALL("hosts.all"),
    /**
     * A placeholder token that represents a single, active master that a
     * component is deployed on. This can be used for cases where text needs to eb
     * rendered with a single master host FQDN inserted.
     */
    HOST_MASTER("hosts.master"),
    /**
     * The version that the stack is being upgraded or downgraded to, such as
     * {@code 2.2.1.0-1234}.
     */
    VERSION("version"),
    /**
     * The lower case of the {@link Direction} value.
     */
    DIRECTION_TEXT("direction.text"),
    /**
     * The proper case of the {@link Direction} value.
     */
    DIRECTION_TEXT_PROPER("direction.text.proper"),
    /**
     * The past tense of the {@link Direction} value.
     */
    DIRECTION_PAST("direction.past"),
    /**
     * The proper past tense of the {@link Direction} value.
     */
    DIRECTION_PAST_PROPER("direction.past.proper"),
    /**
     * The plural tense of the {@link Direction} value.
     */
    DIRECTION_PLURAL("direction.plural"),
    /**
     * The proper plural tense of the {@link Direction} value.
     */
    DIRECTION_PLURAL_PROPER("direction.plural.proper"),
    /**
     * The verbal noun of the {@link Direction} value.
     */
    DIRECTION_VERB("direction.verb"),
    /**
     * The proper verbal noun of the {@link Direction} value.
     */
    DIRECTION_VERB_PROPER("direction.verb.proper");

    private String pattern;
    Placeholder(String key) {
      pattern = "{{" + key + "}}";
    }

    static Placeholder find(String pattern) {
      for (Placeholder p : values()) {
        if (p.pattern.equals(pattern)) {
          return p;
        }
      }

      return OTHER;
    }
  }

  /**
   * Used to render parameter placeholders in {@link ManualTask}s after the
   * {@link StageWrapperBuilder} has finished building out all of the stages.
   */
  @Inject
  private Provider<ConfigHelper> m_configHelperProvider;

  @Inject
  private Provider<AmbariMetaInfo> m_ambariMetaInfoProvider;

  @Inject
  private Provider<Clusters> m_clusters;

  @Inject
  private Provider<RepositoryVersionDAO> m_repoVersionProvider;

  /**
   * Used to update the configuration properties.
   */
  @Inject
  private Provider<AmbariManagementController> m_controllerProvider;

  /**
   * Used to get configurations by service name.
   */
  @Inject
  private ServiceConfigDAO m_serviceConfigDAO;

  /**
   * Get right Upgrade Pack, depends on stack, direction and upgrade type
   * information
   *
   * @param clusterName
   *          The name of the cluster
   * @param upgradeFromVersion
   *          Current stack version
   * @param upgradeToVersion
   *          Target stack version
   * @param direction
   *          {@code Direction} of the upgrade
   * @param upgradeType
   *          The {@code UpgradeType}
   * @param preferredUpgradePackName
   *          For unit test, need to prefer an upgrade pack since multiple
   *          matches can be found.
   * @return {@code UpgradeType} object
   * @throws AmbariException
   */
  public UpgradePack suggestUpgradePack(String clusterName, String upgradeFromVersion, String upgradeToVersion,
    Direction direction, UpgradeType upgradeType, String preferredUpgradePackName) throws AmbariException {

    // Find upgrade packs based on current stack. This is where to upgrade from
    Cluster cluster = m_clusters.get().getCluster(clusterName);
    StackId stack =  cluster.getCurrentStackVersion();

    String repoVersion = upgradeToVersion;

    // TODO AMBARI-12706. Here we need to check, how this would work with SWU Downgrade
    if (direction.isDowngrade() && null != upgradeFromVersion) {
      repoVersion = upgradeFromVersion;
    }

    RepositoryVersionEntity versionEntity = m_repoVersionProvider.get().findByStackNameAndVersion(
        stack.getStackName(), repoVersion);

    if (versionEntity == null) {
      throw new AmbariException(String.format("Repository version %s was not found", repoVersion));
    }

    Map<String, UpgradePack> packs = m_ambariMetaInfoProvider.get().getUpgradePacks(stack.getStackName(), stack.getStackVersion());
    UpgradePack pack = null;

    if (StringUtils.isNotEmpty(preferredUpgradePackName) && packs.containsKey(preferredUpgradePackName)) {
      pack = packs.get(preferredUpgradePackName);
    } else {
      String repoStackId = versionEntity.getStackId().getStackId();
      for (UpgradePack upgradePack : packs.values()) {
        if (null != upgradePack.getTargetStack() && upgradePack.getTargetStack().equals(repoStackId) &&
          upgradeType == upgradePack.getType()) {
          if (null == pack) {
            // Pick the pack.
            pack = upgradePack;
          } else {
            throw new AmbariException(
                String.format("Unable to perform %s. Found multiple upgrade packs for type %s and target version %s",
                    direction.getText(false), upgradeType.toString(), repoVersion));
          }
        }
      }
    }

    if (null == pack) {
      throw new AmbariException(String.format("Unable to perform %s. Could not locate %s upgrade pack for version %s",
          direction.getText(false), upgradeType.toString(), repoVersion));
    }

   return pack;
  }


  /**
   * Generates a list of UpgradeGroupHolder items that are used to execute either
   * an upgrade or a downgrade.
   *
   * @param upgradePack
   *          the upgrade pack
   * @param context
   *          the context that wraps key fields required to perform an upgrade
   * @return the list of holders
   */
  public List<UpgradeGroupHolder> createSequence(UpgradePack upgradePack,
      UpgradeContext context) throws AmbariException {

    Cluster cluster = context.getCluster();
    MasterHostResolver mhr = context.getResolver();

    // Note, only a Rolling Upgrade uses processing tasks.
    Map<String, Map<String, ProcessingComponent>> allTasks = upgradePack.getTasks();
    List<UpgradeGroupHolder> groups = new ArrayList<>();

    for (Grouping group : upgradePack.getGroups(context.getDirection())) {

      // !!! grouping is not scoped to context
      if (!context.isScoped(group.scope)) {
        continue;
      }

      // if there is a condition on the group, evaluate it and skip scheduling
      // of this group if the condition has not been satisfied
      if (null != group.condition && !group.condition.isSatisfied(context)) {
        LOG.info("Skipping {} while building upgrade orchestration due to {}", group, group.condition );
        continue;
      }

      UpgradeGroupHolder groupHolder = new UpgradeGroupHolder();
      groupHolder.name = group.name;
      groupHolder.title = group.title;
      groupHolder.groupClass = group.getClass();
      groupHolder.skippable = group.skippable;
      groupHolder.supportsAutoSkipOnFailure = group.supportsAutoSkipOnFailure;
      groupHolder.allowRetry = group.allowRetry;

      // !!! all downgrades are skippable
      if (context.getDirection().isDowngrade()) {
        groupHolder.skippable = true;
      }

      // Attempt to get the function of the group, during a NonRolling Upgrade
      Task.Type functionName = null;
      if (group instanceof UpgradeFunction) {
        functionName = ((UpgradeFunction) group).getFunction();
      }

      // NonRolling defaults to not performing service checks on a group.
      // Of course, a Service Check Group does indeed run them.
      if (upgradePack.getType() == UpgradeType.NON_ROLLING) {
        group.performServiceCheck = false;
      }

      StageWrapperBuilder builder = group.getBuilder();

      List<UpgradePack.OrderService> services = group.services;

      // Rolling Downgrade must reverse the order of services.
      if (upgradePack.getType() == UpgradeType.ROLLING) {
        if (context.getDirection().isDowngrade() && !services.isEmpty()) {
          List<UpgradePack.OrderService> reverse = new ArrayList<>(services);
          Collections.reverse(reverse);
          services = reverse;
        }
      }

      // !!! cluster and service checks are empty here
      for (UpgradePack.OrderService service : services) {

        if (!context.isServiceSupported(service.serviceName)) {
          continue;
        }

        if (upgradePack.getType() == UpgradeType.ROLLING && !allTasks.containsKey(service.serviceName)) {
          continue;
        }


        for (String component : service.components) {
          // Rolling Upgrade has exactly one task for a Component.
          if (upgradePack.getType() == UpgradeType.ROLLING && !allTasks.get(service.serviceName).containsKey(component)) {
            continue;
          }

          // NonRolling Upgrade has several tasks for the same component, since it must first call Stop, perform several
          // other tasks, and then Start on that Component.

          HostsType hostsType = mhr.getMasterAndHosts(service.serviceName, component);
          if (null == hostsType) {
            continue;
          }

          if (!hostsType.unhealthy.isEmpty()) {
            context.addUnhealthy(hostsType.unhealthy);
          }

          Service svc = cluster.getService(service.serviceName);

          // if a function name is present, build the tasks dynamically;
          // otherwise use the tasks defined in the upgrade pack processing
          ProcessingComponent pc = null;
          if (null == functionName) {
            pc = allTasks.get(service.serviceName).get(component);
          } else {
            // Construct a processing task on-the-fly if it is a "stop" group.
            if (functionName == Type.STOP) {
              pc = new ProcessingComponent();
              pc.name = component;
              pc.tasks = new ArrayList<>();
              pc.tasks.add(new StopTask());
            } else {
              // For Start and Restart, make a best attempt at finding
              // Processing Components.
              // If they don't exist, make one on the fly.
              if (allTasks.containsKey(service.serviceName)
                  && allTasks.get(service.serviceName).containsKey(component)) {
                pc = allTasks.get(service.serviceName).get(component);
              } else {
                // Construct a processing task on-the-fly so that the Upgrade
                // Pack is less verbose.
                pc = new ProcessingComponent();
                pc.name = component;
                pc.tasks = new ArrayList<>();

                if (functionName == Type.START) {
                  pc.tasks.add(new StartTask());
                }
                if (functionName == Type.RESTART) {
                  pc.tasks.add(new RestartTask());
                }
              }
            }
          }

          if (pc == null) {
            LOG.error(MessageFormat.format("Couldn't create a processing component for service {0} and component {1}.", service.serviceName, component));
            continue;
          }

          setDisplayNames(context, service.serviceName, component);

          // Special case for NAMENODE when there are multiple
          if (service.serviceName.equalsIgnoreCase("HDFS") && component.equalsIgnoreCase("NAMENODE")) {

            // Rolling Upgrade requires first upgrading the Standby, then the Active NameNode.
            // Whereas NonRolling needs to do the following:
            //   NameNode HA:  Pick one to the be active, and the other the standby.
            //   Non-NameNode HA: Upgrade first the SECONDARY, then the primary NAMENODE
            switch (upgradePack.getType()) {
              case ROLLING:
                if (!hostsType.hosts.isEmpty() && hostsType.master != null && hostsType.secondary != null) {
                  // The order is important, first do the standby, then the active namenode.
                  LinkedHashSet<String> order = new LinkedHashSet<>();

                  order.add(hostsType.secondary);
                  order.add(hostsType.master);

                  // Override the hosts with the ordered collection
                  hostsType.hosts = order;

                  builder.add(context, hostsType, service.serviceName,
                      svc.isClientOnlyService(), pc, null);
                } else {
                  LOG.warn("Could not orchestrate NameNode.  Hosts could not be resolved: hosts={}, active={}, standby={}",
                      StringUtils.join(hostsType.hosts, ','), hostsType.master, hostsType.secondary);
                }
                break;
              case NON_ROLLING:
                boolean isNameNodeHA = mhr.isNameNodeHA();
                if (isNameNodeHA && hostsType.master != null && hostsType.secondary != null) {
                  // This could be any order, but the NameNodes have to know what role they are going to take.
                  // So need to make 2 stages, and add different parameters to each one.

                  HostsType ht1 = new HostsType();
                  LinkedHashSet<String> h1Hosts = new LinkedHashSet<>();
                  h1Hosts.add(hostsType.master);
                  ht1.hosts = h1Hosts;
                  Map<String, String> h1Params = new HashMap<>();
                  h1Params.put("desired_namenode_role", "active");

                  HostsType ht2 = new HostsType();
                  LinkedHashSet<String> h2Hosts = new LinkedHashSet<>();
                  h2Hosts.add(hostsType.secondary);
                  ht2.hosts = h2Hosts;
                  Map<String, String> h2Params = new HashMap<>();
                  h2Params.put("desired_namenode_role", "standby");


                  builder.add(context, ht1, service.serviceName,
                      svc.isClientOnlyService(), pc, h1Params);

                  builder.add(context, ht2, service.serviceName,
                      svc.isClientOnlyService(), pc, h2Params);
                } else {
                  // If no NameNode HA, then don't need to change hostsType.hosts since there should be exactly one.
                  builder.add(context, hostsType, service.serviceName,
                      svc.isClientOnlyService(), pc, null);
                }

                break;
            }
          } else {
            builder.add(context, hostsType, service.serviceName,
                svc.isClientOnlyService(), pc, null);
          }
        }
      }

      List<StageWrapper> proxies = builder.build(context);

      if (CollectionUtils.isNotEmpty(proxies)) {
        groupHolder.items = proxies;
        postProcess(context, groupHolder);
        groups.add(groupHolder);
      }
    }

    if (LOG.isDebugEnabled()) {
      for (UpgradeGroupHolder group : groups) {
        LOG.debug(group.name);

        int i = 0;
        for (StageWrapper proxy : group.items) {
          LOG.debug("  Stage {}", Integer.valueOf(i++));
          int j = 0;

          for (TaskWrapper task : proxy.getTasks()) {
            LOG.debug("    Task {} {}", Integer.valueOf(j++), task);
          }
        }
      }
    }

    return groups;
  }

  /**
   * Walks through the UpgradeGroupHolder and updates titles and manual tasks,
   * replacing keyword tokens needed for display purposes
   *
   * @param ctx     the upgrade context
   * @param holder  the upgrade holder
   */
  private void postProcess(UpgradeContext ctx, UpgradeGroupHolder holder) {

    holder.title = tokenReplace(ctx, holder.title, null, null);

    for (StageWrapper stageWrapper : holder.items) {
      if (null != stageWrapper.getText()) {
        stageWrapper.setText(tokenReplace(ctx, stageWrapper.getText(),
            null, null));
      }

      for (TaskWrapper taskWrapper : stageWrapper.getTasks()) {
        for (Task task : taskWrapper.getTasks()) {
          if (null != task.summary) {
            task.summary = tokenReplace(ctx, task.summary, null, null);
          }

          if (task.getType() == Type.MANUAL) {
            ManualTask mt = (ManualTask) task;
            if(null != mt.messages && !mt.messages.isEmpty()){
              for(int i = 0; i < mt.messages.size(); i++){
                String message =  mt.messages.get(i);
                message = tokenReplace(ctx, message, taskWrapper.getService(), taskWrapper.getComponent());
                mt.messages.set(i, message);
              }
            }
          }
        }
      }
    }
  }

  /**
   * @param ctx       the upgrade context
   * @param source    the source string to replace tokens on
   * @param service   the service name if required
   * @param component the component name if required
   * @return the source string with tokens replaced, if any are found
   */
  private String tokenReplace(UpgradeContext ctx, String source, String service, String component) {
    Cluster cluster = ctx.getCluster();
    MasterHostResolver mhr = ctx.getResolver();

    String result = source;

    List<String> tokens = new ArrayList<>(5);
    Matcher matcher = PLACEHOLDER_REGEX.matcher(source);
    while (matcher.find()) {
      tokens.add(matcher.group(1));
    }

    // iterate through all of the matched tokens
    for (String token : tokens) {
      String value = null;

      Placeholder p = Placeholder.find(token);
      switch (p) {
        case HOST_ALL: {
          if (null != service && null != component) {
            HostsType hostsType = mhr.getMasterAndHosts(service, component);

            if (null != hostsType) {
              value = StringUtils.join(hostsType.hosts, ", ");
            }
          }
          break;
        }
        case HOST_MASTER: {
          if (null != service && null != component) {
            HostsType hostsType = mhr.getMasterAndHosts(service, component);

            if (null != hostsType) {
              value = hostsType.master;
            }
          }
          break;
        }
        case VERSION:
          value = ctx.getRepositoryVersion().getVersion();
          break;
        case DIRECTION_VERB:
        case DIRECTION_VERB_PROPER:
          value = ctx.getDirection().getVerb(p == Placeholder.DIRECTION_VERB_PROPER);
          break;
        case DIRECTION_PAST:
        case DIRECTION_PAST_PROPER:
          value = ctx.getDirection().getPast(p == Placeholder.DIRECTION_PAST_PROPER);
          break;
        case DIRECTION_PLURAL:
        case DIRECTION_PLURAL_PROPER:
          value = ctx.getDirection().getPlural(p == Placeholder.DIRECTION_PLURAL_PROPER);
          break;
        case DIRECTION_TEXT:
        case DIRECTION_TEXT_PROPER:
          value = ctx.getDirection().getText(p == Placeholder.DIRECTION_TEXT_PROPER);
          break;
        default:
          value = m_configHelperProvider.get().getPlaceholderValueFromDesiredConfigurations(
              cluster, token);
          break;
      }

      // replace the token in the message with the value
      if (null != value) {
        result = result.replace(token, value);
      }
    }

    return result;
  }

  /**
   * Short-lived objects that hold information about upgrade groups
   */
  public static class UpgradeGroupHolder {
    /**
     * The name
     */
    public String name;
    /**
     * The title
     */
    public String title;


    public Class<? extends Grouping> groupClass;

    /**
     * Indicate whether retry is allowed for the stages in this group.
     */
    public boolean allowRetry = true;

    /**
     * Indicates whether the stages in this group are skippable on failure.  If a
     * stage is skippable, a failed result can be skipped without failing the entire upgrade.
     */
    public boolean skippable = false;

    /**
     * {@code true} if the upgrade group's tasks can be automatically skipped if
     * they fail. This is used in conjunction with
     * {@link UpgradePack#isComponentFailureAutoSkipped()}. If the upgrade pack
     * (or the upgrade request) does support auto skipping failures, then this
     * setting has no effect. It's used mainly as a way to ensure that some
     * groupings never have their failed tasks automatically skipped.
     */
    public boolean supportsAutoSkipOnFailure = true;

    /**
     * List of stages for the group
     */
    public List<StageWrapper> items = new ArrayList<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      StringBuilder buffer = new StringBuilder("UpgradeGroupHolder{");
      buffer.append("name=").append(name);
      buffer.append(", title=").append(title);
      buffer.append(", allowRetry=").append(allowRetry);
      buffer.append(", skippable=").append(skippable);
      buffer.append("}");
      return buffer.toString();
    }
  }

  /**
   * Get a single resource for the task with the given parameters.
   * @param clusterName Cluster Name
   * @param requestId Request Id
   * @param stageId Stage Id
   * @param taskId Task Id
   * @return Single task resource that matches the predicates, otherwise, null.
   */
  public Resource getTaskResource(String clusterName, Long requestId, Long stageId, Long taskId)
      throws UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException, SystemException {
    ClusterController clusterController = ClusterControllerHelper.getClusterController();

    Request request = PropertyHelper.getReadRequest();

    Predicate p1 = new PredicateBuilder().property(TaskResourceProvider.TASK_CLUSTER_NAME_PROPERTY_ID).equals(clusterName).toPredicate();
    Predicate p2 = new PredicateBuilder().property(TaskResourceProvider.TASK_REQUEST_ID_PROPERTY_ID).equals(requestId.toString()).toPredicate();
    Predicate p3 = new PredicateBuilder().property(TaskResourceProvider.TASK_STAGE_ID_PROPERTY_ID).equals(stageId.toString()).toPredicate();
    Predicate p4 = new PredicateBuilder().property(TaskResourceProvider.TASK_ID_PROPERTY_ID).equals(taskId.toString()).toPredicate();

    QueryResponse response = clusterController.getResources(Resource.Type.Task,
        request, new AndPredicate(p1, p2, p3, p4));

    Set<Resource> task = response.getResources();
    return task.size() == 1 ? task.iterator().next() : null;
  }

  /**
   * Helper to set service and component display names on the context
   * @param context   the context to update
   * @param service   the service name
   * @param component the component name
   */
  private void setDisplayNames(UpgradeContext context, String service, String component) {
    StackId stackId = context.getCluster().getDesiredStackVersion();
    try {
      ServiceInfo serviceInfo = m_ambariMetaInfoProvider.get().getService(stackId.getStackName(),
          stackId.getStackVersion(), service);
      context.setServiceDisplay(service, serviceInfo.getDisplayName());

      ComponentInfo compInfo = serviceInfo.getComponentByName(component);
      context.setComponentDisplay(service, component, compInfo.getDisplayName());

    } catch (AmbariException e) {
      LOG.debug("Could not get service detail", e);
    }
  }

  /**
   * Updates the various repositories and configurations for services
   * participating in the upgrade or downgrade. The following actions are
   * performed in order:
   * <ul>
   * <li>The desired repository for every service and component is changed<
   * <li>The {@link UpgradeState} of every component host is moved to either
   * {@link UpgradeState#IN_PROGRESS} or {@link UpgradeState#NONE}.
   * <li>In the case of an upgrade, new configurations and service
   * configurations are created if necessary. In the case of a downgrade, any
   * configurations created by the upgrade are reverted.
   * </ul>
   *
   * @param upgradeContext
   *          the upgrade context holding all relevent upgrade information (not
   *          {@code null}).
   * @throws AmbariException
   */
  @Transactional
  @Experimental(feature = ExperimentalFeature.PATCH_UPGRADES)
  public void updateDesiredRepositoriesAndConfigs(UpgradeContext upgradeContext)
      throws AmbariException {
    setDesiredRepositories(upgradeContext);
    processConfigurationsIfRequired(upgradeContext);
  }

  /**
   * Transitions all affected components to {@link UpgradeState#IN_PROGRESS}.
   * Transition is performed only for components that advertise their version.
   * Additionally sets the service component desired version to the specified
   * argument.
   * <p/>
   * Because this iterates over all of the components on every host and updates
   * the upgrade state individually, we wrap this method inside of a transaction
   * to prevent 1000's of transactions from being opened and committed.
   *
   * @param upgradeContext
   *          the upgrade context (not {@code null}).
   */
  @Experimental(feature = ExperimentalFeature.PATCH_UPGRADES)
  private void setDesiredRepositories(UpgradeContext upgradeContext) throws AmbariException {
    Cluster cluster = upgradeContext.getCluster();
    Set<String> services = upgradeContext.getSupportedServices();

    for (String serviceName : services) {
      Service service = cluster.getService(serviceName);
      RepositoryVersionEntity targetRepositoryVersion = upgradeContext.getTargetRepositoryVersion(serviceName);
      StackId targetStack = targetRepositoryVersion.getStackId();
      service.setDesiredRepositoryVersion(targetRepositoryVersion);

      Collection<ServiceComponent> components = service.getServiceComponents().values();
      for (ServiceComponent serviceComponent : components) {
        boolean versionAdvertised = false;
        try {
          ComponentInfo ci = m_ambariMetaInfoProvider.get().getComponent(targetStack.getStackName(),
              targetStack.getStackVersion(), serviceComponent.getServiceName(),
              serviceComponent.getName());

          versionAdvertised = ci.isVersionAdvertised();
        } catch (AmbariException e) {
          LOG.warn("Component {}/{} doesn't exist for stack {}.  Setting version to {}",
              serviceComponent.getServiceName(), serviceComponent.getName(), targetStack,
              StackVersionListener.UNKNOWN_VERSION);
        }

        UpgradeState upgradeStateToSet = UpgradeState.IN_PROGRESS;
        if (!versionAdvertised) {
          upgradeStateToSet = UpgradeState.NONE;
        }

        for (ServiceComponentHost serviceComponentHost : serviceComponent.getServiceComponentHosts().values()) {
          if (serviceComponentHost.getUpgradeState() != upgradeStateToSet) {
            serviceComponentHost.setUpgradeState(upgradeStateToSet);
          }

          // !!! if we aren't version advertised, but there IS a version, set
          // it.
          if (!versionAdvertised && !StringUtils.equals(StackVersionListener.UNKNOWN_VERSION,
              serviceComponentHost.getVersion())) {
            serviceComponentHost.setVersion(StackVersionListener.UNKNOWN_VERSION);
          }
        }

        // set component desired repo
        serviceComponent.setDesiredRepositoryVersion(targetRepositoryVersion);
      }
    }
  }

  /**
   * Handles the creation or resetting of configurations based on whether an
   * upgrade or downgrade is occurring. This method will not do anything when
   * the service is not crossing major stack versions, since, by definition, no
   * new configurations are automatically created when upgrading with the same
   * stack (ie HDP 2.2.0.0 -> HDP 2.2.1.0).
   * <p/>
   * When upgrading or downgrade between stacks (HDP 2.2.0.0 -> HDP 2.3.0.0)
   * then this will perform the following:
   * <ul>
   * <li>Upgrade: Create new configurations that are a merge between the source
   * stack and the target stack. If a value has changed between stacks, then the
   * target stack value should be taken unless the cluster's value differs from
   * the old stack. This can occur if a property has been customized after
   * installation.</li>
   * <li>Downgrade: Reset the latest configurations from the service's original
   * stack. The new configurations that were created on upgrade must be left
   * intact until all components have been reverted, otherwise heartbeats will
   * fail due to missing configurations.</li>
   * </ul>
   *
   * @param upgradeContext
   *          the upgrade context (not {@code null}).
   * @throws AmbariException
   */
  private void processConfigurationsIfRequired(UpgradeContext upgradeContext)
      throws AmbariException {

    AmbariManagementController controller = m_controllerProvider.get();

    Cluster cluster = upgradeContext.getCluster();
    Direction direction = upgradeContext.getDirection();
    String userName = controller.getAuthName();
    Set<String> servicesInUpgrade = upgradeContext.getSupportedServices();

    // merge or revert configurations for any service that needs it
    for( String serviceName : servicesInUpgrade ){
      RepositoryVersionEntity sourceRepositoryVersion = upgradeContext.getSourceRepositoryVersion(serviceName);
      RepositoryVersionEntity targetRepositoryVersion = upgradeContext.getTargetRepositoryVersion(serviceName);
      StackId sourceStackId = sourceRepositoryVersion.getStackId();
      StackId targetStackId = targetRepositoryVersion.getStackId();

      // only work with configurations when crossing stacks
      if (sourceStackId.equals(targetStackId)) {
        RepositoryVersionEntity associatedRepositoryVersion = upgradeContext.getRepositoryVersion();
        LOG.info(
            "The {} {} {} will not change stack configurations for {} since the source and target are both {}",
            direction.getText(false), direction.getPreposition(),
            associatedRepositoryVersion.getVersion(), serviceName, targetStackId);

        continue;
      }

      ConfigHelper configHelper = m_configHelperProvider.get();

      // downgrade is easy - just remove the new and make the old current
      if (direction == Direction.DOWNGRADE) {
        cluster.applyLatestConfigurations(targetStackId, serviceName);
        return;
      }

      // upgrade is a bit harder - we have to merge new stack configurations in

      // populate a map of default configurations for the service on the old
      // stack (this is used when determining if a property has been
      // customized and should be overriden with the new stack value)
      Map<String, Map<String, String>> oldServiceDefaultConfigsByType = configHelper.getDefaultProperties(
          sourceStackId, serviceName);

      // populate a map with default configurations from the new stack
      Map<String, Map<String, String>> newServiceDefaultConfigsByType = configHelper.getDefaultProperties(
          targetStackId, serviceName);

      // find the current, existing configurations for the service
      List<Config> existingServiceConfigs = new ArrayList<>();
      List<ServiceConfigEntity> latestServiceConfigs = m_serviceConfigDAO.getLastServiceConfigsForService(
          cluster.getClusterId(), serviceName);

      for (ServiceConfigEntity serviceConfig : latestServiceConfigs) {
        List<ClusterConfigEntity> existingConfigurations = serviceConfig.getClusterConfigEntities();
        for (ClusterConfigEntity currentServiceConfig : existingConfigurations) {
          String configurationType = currentServiceConfig.getType();
          Config currentClusterConfigForService = cluster.getDesiredConfigByType(configurationType);
          existingServiceConfigs.add(currentClusterConfigForService);
        }
      }

      // now that we have found, old, new, and existing confgs, overlay the
      // existing on top of the new
      for (Config existingServiceConfig : existingServiceConfigs) {
        String configurationType = existingServiceConfig.getType();

        // get current stack default configurations on install
        Map<String, String> oldServiceDefaultConfigs = oldServiceDefaultConfigsByType.get(
            configurationType);

        // NPE sanity for current stack defaults
        if (null == oldServiceDefaultConfigs) {
          oldServiceDefaultConfigs = Collections.emptyMap();
        }

        // get the existing configurations
        Map<String, String> existingConfigurations = existingServiceConfig.getProperties();

        // get the new configurations
        Map<String, String> newDefaultConfigurations = newServiceDefaultConfigsByType.get(
            configurationType);

        // if the new stack configurations don't have the type, then simply add
        // all of the existing in
        if (null == newDefaultConfigurations) {
          newServiceDefaultConfigsByType.put(configurationType, existingConfigurations);
          continue;
        } else {
          // Remove any configs in the new stack whose value is NULL, unless
          // they currently exist and the value is not NULL.
          Iterator<Map.Entry<String, String>> iter = newDefaultConfigurations.entrySet().iterator();
          while (iter.hasNext()) {
            Map.Entry<String, String> entry = iter.next();
            if (entry.getValue() == null) {
              iter.remove();
            }
          }
        }

        // process every existing configuration property for this configuration
        // type
        for (Map.Entry<String, String> existingConfigurationEntry : existingConfigurations.entrySet()) {
          String existingConfigurationKey = existingConfigurationEntry.getKey();
          String existingConfigurationValue = existingConfigurationEntry.getValue();

          // if there is already an entry, we now have to try to determine if
          // the value was customized after stack installation
          if (newDefaultConfigurations.containsKey(existingConfigurationKey)) {
            String newDefaultConfigurationValue = newDefaultConfigurations.get(
                existingConfigurationKey);

            if (!StringUtils.equals(existingConfigurationValue, newDefaultConfigurationValue)) {
              // the new default is different from the existing cluster value;
              // only override the default value if the existing value differs
              // from the original stack
              String oldDefaultValue = oldServiceDefaultConfigs.get(existingConfigurationKey);

              if (!StringUtils.equals(existingConfigurationValue, oldDefaultValue)) {
                // at this point, we've determined that there is a
                // difference
                // between default values between stacks, but the value was
                // also customized, so keep the customized value
                newDefaultConfigurations.put(existingConfigurationKey, existingConfigurationValue);
              }
            }
          } else {
            // there is no entry in the map, so add the existing key/value
            // pair
            newDefaultConfigurations.put(existingConfigurationKey, existingConfigurationValue);
          }
        }

        /*
        for every new configuration which does not exist in the existing
        configurations, see if it was present in the current stack

        stack 2.x has foo-site/property (on-ambari-upgrade is false)
        stack 2.y has foo-site/property
        the current cluster (on 2.x) does not have it

        In this case, we should NOT add it back as clearly stack advisor has removed it
        */
        Iterator<Map.Entry<String, String>> newDefaultConfigurationsIterator = newDefaultConfigurations.entrySet().iterator();
        while (newDefaultConfigurationsIterator.hasNext()) {
          Map.Entry<String, String> newConfigurationEntry = newDefaultConfigurationsIterator.next();
          String newConfigurationPropertyName = newConfigurationEntry.getKey();
          if (oldServiceDefaultConfigs.containsKey(newConfigurationPropertyName)
              && !existingConfigurations.containsKey(newConfigurationPropertyName)) {
            LOG.info(
                "The property {}/{} exists in both {} and {} but is not part of the current set of configurations and will therefore not be included in the configuration merge",
                configurationType, newConfigurationPropertyName, sourceStackId, targetStackId);

            // remove the property so it doesn't get merged in
            newDefaultConfigurationsIterator.remove();
          }
        }
      }

      if (null != newServiceDefaultConfigsByType) {
        Set<String> configTypes = newServiceDefaultConfigsByType.keySet();
        LOG.info("The upgrade will create the following configurations for stack {}: {}",
            targetStackId, StringUtils.join(configTypes, ','));

        configHelper.createConfigTypes(cluster, controller, targetStackId,
            newServiceDefaultConfigsByType, userName, "Configuration created for Upgrade");
      }
    }
  }
}
