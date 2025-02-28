package org.jfrog.bamboo.configuration;

import com.atlassian.bamboo.build.Job;
import com.atlassian.bamboo.collections.ActionParametersMap;
import com.atlassian.bamboo.configuration.AdministrationConfiguration;
import com.atlassian.bamboo.credentials.CredentialsAccessor;
import com.atlassian.bamboo.repository.NameValuePair;
import com.atlassian.bamboo.task.*;
import com.atlassian.bamboo.v2.build.agent.capability.Requirement;
import com.atlassian.bamboo.ww2.actions.build.admin.create.UIConfigSupport;
import com.atlassian.sal.api.message.I18nResolver;
import com.atlassian.spring.container.ContainerManager;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfrog.bamboo.admin.ServerConfigManager;
import org.jfrog.bamboo.configuration.util.TaskConfiguratorHelperImpl;
import org.jfrog.bamboo.context.ArtifactoryBuildContext;
import org.jfrog.bamboo.context.GenericContext;
import org.jfrog.bamboo.context.PackageManagersContext;
import org.jfrog.bamboo.release.vcs.VcsTypes;
import org.jfrog.bamboo.release.vcs.git.GitAuthenticationType;
import org.jfrog.bamboo.security.EncryptionHelper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.jfrog.bamboo.context.PackageManagersContext.BUILD_INFO_AGGREGATION;
import static org.jfrog.bamboo.context.PackageManagersContext.PUBLISH_BUILD_INFO_PARAM;

/**
 * Base class for all {@link com.atlassian.bamboo.task.TaskConfigurator}s that are used by the plugin. It sets the
 * {@link ServerConfigManager} to be used for populating the Artifactory relevant fields. It also serves as a common
 * ground for setting common fields in the context of the build.
 *
 * @author Tomer Cohen
 */
public abstract class AbstractArtifactoryConfiguration extends AbstractTaskConfigurator implements
        TaskTestResultsSupport, BuildTaskRequirementSupport {

    protected I18nResolver i18nResolver;
    public static final String CFG_TEST_RESULTS_FILE_PATTERN_OPTION_CUSTOM = "customTestDirectory";
    public static final String CFG_TEST_RESULTS_FILE_PATTERN_OPTION_STANDARD = "standardTestDirectory";
    private static final Map<String, String> TEST_RESULTS_FILE_PATTERN_TYPES = ImmutableMap
            .of(CFG_TEST_RESULTS_FILE_PATTERN_OPTION_STANDARD, "Look in the standard test results directory.",
                    CFG_TEST_RESULTS_FILE_PATTERN_OPTION_CUSTOM, "Specify custom results directories");
    public static final String CFG_LEGACY_PATTERNS = "legacyPatterns";
    public static final String CFG_FILE_SPECS = "specs";
    public static final Map<String, String> USE_SPECS_OPTIONS = ImmutableMap.of(CFG_FILE_SPECS, "Specs", CFG_LEGACY_PATTERNS, "Legacy patterns (deprecated)");

    // If selected, use the credentials configured in the global Artifactory servers configuration
    public static final String CVG_CRED_NO_OVERRIDE = "noOverriding";
    // If selected, override username and password as configured in the job configuration
    public static final String CVG_CRED_USERNAME_PASSWORD = "usernamePassword";
    // If selected, use the shared credentials chosen in the job configuration
    public static final String CVG_CRED_SHARED_CREDENTIALS = "sharedCredentials";
    // Map between credential overriding options to description
    public static final Map<String, String> CFG_OVERRIDE_CREDENTIALS_OPTIONS = ImmutableMap.of(CVG_CRED_NO_OVERRIDE, "No overriding", CVG_CRED_USERNAME_PASSWORD, "Provide username and password", CVG_CRED_SHARED_CREDENTIALS, "Use shared credentials");
    public static final String CFG_SPEC_SOURCE_FILE = "file";
    public static final String CFG_SPEC_SOURCE_JOB_CONFIGURATION = "jobConfiguration";
    public static final Map<String, String> CFG_SPEC_SOURCE = ImmutableMap.of(CFG_SPEC_SOURCE_JOB_CONFIGURATION, "Task configuration", CFG_SPEC_SOURCE_FILE, "File");
    public static final Map<String, String> SIGN_METHOD_MAP = ImmutableMap.of("false", "Don't Sign", "true", "Sign");
    public static final String SIGN_METHOD_MAP_KEY = "signMethods";
    protected transient ServerConfigManager serverConfigManager;
    protected transient CredentialsAccessor credentialsAccessor;
    protected AdministrationConfiguration administrationConfiguration;
    protected UIConfigSupport uiConfigSupport;
    private final String builderContextPrefix;
    private final String capabilityPrefix;
    private static final Logger log = LogManager.getLogger(AbstractArtifactoryConfiguration.class);
    protected TaskConfiguratorHelperImpl taskConfiguratorHelper = new TaskConfiguratorHelperImpl();

    protected AbstractArtifactoryConfiguration() {
        this(null, null);
    }

    protected AbstractArtifactoryConfiguration(String builderContextPrefix, @Nullable String capabilityPrefix) {
        serverConfigManager = ServerConfigManager.getInstance();
        if (administrationConfiguration == null) {
            administrationConfiguration =
                    (AdministrationConfiguration) ContainerManager.getComponent("administrationConfiguration");
        }
        this.builderContextPrefix = builderContextPrefix;
        this.capabilityPrefix = capabilityPrefix;
    }

    public String getTestDirectory(PackageManagersContext buildContext) {
        String directoryOption = buildContext.getTestDirectoryOption();
        if (CFG_TEST_RESULTS_FILE_PATTERN_OPTION_STANDARD.equals(directoryOption)) {
            return getDefaultTestDirectory();
        } else if (CFG_TEST_RESULTS_FILE_PATTERN_OPTION_CUSTOM.equals(directoryOption)) {
            return buildContext.getTestDirectory();
        }
        return null;
    }

    @Override
    public void populateContextForEdit(@NotNull Map<String, Object> context, @NotNull TaskDefinition taskDefinition) {
        super.populateContextForEdit(context, taskDefinition);
        serverConfigManager = ServerConfigManager.getInstance();
        populateContextForAllOperations(context);
    }

    /**
     * Build-Info aggregation is supported since version 2.7.0 of the plugin.
     * This means that for tasks which were created before this version,
     * publish build-info is done directly by the task and not by the
     * Artifactory Build Info Publish' task, introduced in version 2.7.0.
     * The following code takes care of backward compatibility, for tasks which were created before 2.7.0.
     */
    public void populateLegacyContextForEdit(@NotNull Map<String, Object> context, @NotNull TaskDefinition taskDefinition) {
        if (!Boolean.parseBoolean(taskDefinition.getConfiguration().get(BUILD_INFO_AGGREGATION))) {
            context.put(PackageManagersContext.CAPTURE_BUILD_INFO, false);
            context.put(PUBLISH_BUILD_INFO_PARAM, true);
        }
    }

    @Override
    public void populateContextForCreate(@NotNull Map<String, Object> context) {
        super.populateContextForCreate(context);
        serverConfigManager = ServerConfigManager.getInstance();
        populateContextForAllOperations(context);
    }

    /**
     * Populate new task context with values required for tasks having legacy mode (prior to 2.7.0).
     * These values are later used to determine whether a task runs in legacy mode or not.
     */
    public void populateLegacyContextForCreate(@NotNull Map<String, Object> context) {
        context.put(BUILD_INFO_AGGREGATION, true);
        context.put(PackageManagersContext.CAPTURE_BUILD_INFO, true);
        context.put(PUBLISH_BUILD_INFO_PARAM, false);
    }

    @NotNull
    @Override
    public Map<String, String> generateTaskConfigMap(@NotNull ActionParametersMap params,
                                                     @Nullable TaskDefinition previousTaskDefinition) {
        Map<String, String> taskConfigMap = super.generateTaskConfigMap(params, previousTaskDefinition);
        taskConfigMap.put("baseUrl", administrationConfiguration.getBaseUrl());

        return taskConfigMap;
    }

    @NotNull
    @Override
    public Set<Requirement> calculateRequirements(@NotNull TaskDefinition taskDefinition, @NotNull Job job) {
        Set<Requirement> requirements = Sets.newHashSet();
        if (StringUtils.isNotBlank(builderContextPrefix)) {
            taskConfiguratorHelper.addJdkRequirement(requirements, taskDefinition,
                    builderContextPrefix + TaskConfigConstants.CFG_JDK_LABEL);
            if (StringUtils.isNotBlank(capabilityPrefix)) {
                taskConfiguratorHelper.addSystemRequirementFromConfiguration(requirements, taskDefinition,
                        builderContextPrefix + PackageManagersContext.EXECUTABLE, capabilityPrefix);
            }
        }
        return requirements;
    }

    protected void populateContextWithConfiguration(@NotNull Map<String, Object> context,
                                                    @NotNull TaskDefinition taskDefinition, Set<String> fieldsToCopy) {

        // Encrypt the password fields, so that they do not appear as free-text on the task configuration UI.
        encryptFields(taskDefinition.getConfiguration());
        taskConfiguratorHelper.populateContextWithConfiguration(context, taskDefinition, fieldsToCopy);
        // Decrypt back the password fields.
        decryptFields(taskDefinition.getConfiguration());
    }

    // populate common objects into context
    private void populateContextForAllOperations(@NotNull Map<String, Object> context) {
        context.put("uiConfigBean", uiConfigSupport);
        context.put("testDirectoryTypes", TEST_RESULTS_FILE_PATTERN_TYPES);
        context.put(PackageManagersContext.ENV_VARS_EXCLUDE_PATTERNS, PackageManagersContext.ENV_VARS_TO_EXCLUDE);
        context.put(ArtifactoryBuildContext.BUILD_NAME, ArtifactoryBuildContext.DEFAULT_BUILD_NAME);
        context.put(ArtifactoryBuildContext.BUILD_NUMBER, ArtifactoryBuildContext.DEFAULT_BUILD_NUMBER);
        context.put(SIGN_METHOD_MAP_KEY, SIGN_METHOD_MAP);
        context.put("overrideCredentialsOptions", CFG_OVERRIDE_CREDENTIALS_OPTIONS);
        context.put("useSpecsOptions", USE_SPECS_OPTIONS);
        context.put(GenericContext.USE_SPECS_CHOICE, CFG_FILE_SPECS);
        context.put("specSourceOptions", CFG_SPEC_SOURCE);
        context.put(GenericContext.SPEC_SOURCE_CHOICE, CFG_SPEC_SOURCE_JOB_CONFIGURATION);
        context.put("credentialsAccessor", credentialsAccessor);
    }

    /**
     * Sets the UI config bean from bamboo. NOTE: This method is called from Bamboo upon instantiation of this class by
     * reflection.
     *
     * @param uiConfigSupport The UI config bean for select values.
     */
    public void setUiConfigSupport(UIConfigSupport uiConfigSupport) {
        this.uiConfigSupport = uiConfigSupport;
    }

    /**
     * Sets the credentials accessor from bamboo. NOTE: This method is called from Bamboo upon instantiation of this class by
     * reflection.
     *
     * @param credentialsAccessor - Bamboo credentials accessor
     */
    public void setCredentialsAccessor(final CredentialsAccessor credentialsAccessor) {
        this.credentialsAccessor = credentialsAccessor;
    }

    public void setAdministrationConfiguration(AdministrationConfiguration administrationConfiguration) {
        this.administrationConfiguration = administrationConfiguration;
    }

    protected String readFileByKey(final ActionParametersMap params, String keyToRead) {
        final File private_key_file = params.getFiles().get(keyToRead);
        if (private_key_file != null) {
            try {
                return FileUtils.readFileToString(private_key_file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Cannot read uploaded file", e);
            }
        } else {
            log.error("Unable to load file from config submission!");
        }
        return null;
    }

    private boolean isEncrypted(String value) {
        String decryptedValue = EncryptionHelper.decryptIfNeeded(value);
        return !decryptedValue.equals(value);
    }

    /**
     * This method is used by the encryptFields and decryptFields methods.
     * It encrypts or decrypts the task config fields, if their key ends with 'password'.
     * While encrypting / decrypting, if the keys are already encrypted / decrypted,
     * the keys values will not change.
     *
     * @param taskConfigMap The task config fields map.
     * @param enc           If true - encrypt, else - decrypt.
     */
    private void encOrDecFields(Map<String, String> taskConfigMap, boolean enc) {
        for (Map.Entry<String, String> entry : taskConfigMap.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if (shouldEncrypt(key)) {
                String value = entry.getValue();
                if (isEncrypted(value)) {
                    value = EncryptionHelper.decryptIfNeeded(value);
                }
                if (enc) {
                    value = EncryptionHelper.encryptForUi(value);
                }
                entry.setValue(value);
            }
        }
    }

    private boolean shouldEncrypt(String key) {
        return key.contains("artifactory") && (key.contains("password") || key.contains("ssh"));
    }

    /**
     * Encrypt the task config fields, if their key ends with 'password'.
     * If the keys are already encrypted, their value will not change.
     *
     * @param taskConfigMap The task config fields map.
     */
    private void encryptFields(Map<String, String> taskConfigMap) {
        encOrDecFields(taskConfigMap, true);
    }

    /**
     * Decrypt the task config fields, if their key ends with 'password'.
     * If the keys are already decrypted, their value will not change.
     *
     * @param taskConfigMap The task config fields map.
     */
    protected void decryptFields(Map<String, String> taskConfigMap) {
        encOrDecFields(taskConfigMap, false);
    }

    /**
     * Reset the build context configuration of the deployer back to the default values if no server id was selected
     *
     * @param buildContext The build context which holds the environment for the configuration.
     */
    protected void resetDeployerConfigIfNeeded(PackageManagersContext buildContext) {
        long serverId = buildContext.getArtifactoryServerId();
        if (serverId == -1) {
            buildContext.resetDeployerContextToDefault();
        }
    }

    /**
     * Reset the build context configuration of the resolver back to the default values if no server id was selected
     *
     * @param buildContext The build context which holds the environment for the configuration.
     */
    protected void resetResolverConfigIfNeeded(PackageManagersContext buildContext) {
        long serverId = buildContext.getArtifactoryServerId();
        if (serverId == -1) {
            buildContext.resetResolverContextToDefault();
        }
    }

    /**
     * @return The unique key identifier of the task configuration.
     */
    protected abstract String getKey();

    protected String getDefaultTestDirectory() {
        throw new UnsupportedOperationException("This method is not implemented for class " + this.getClass());
    }

    protected List<NameValuePair> getGitAuthenticationTypes() {
        return Arrays.stream(GitAuthenticationType.values())
                .map(Enum::name)
                .map(name -> new NameValuePair(name, getAuthTypeName(name)))
                .collect(Collectors.toList());
    }

    protected List<NameValuePair> getVcsTypes() {
        return Arrays.stream(VcsTypes.values())
                .map(Enum::name)
                .map(name -> new NameValuePair(name, getVcsTypeName(name)))
                .collect(Collectors.toList());
    }

    protected Map<String, String> getSshFileContent(ActionParametersMap params, TaskDefinition previousTaskDefinition) {
        Map<String, String> taskConfigMap = new HashMap<>();
        String sshFileKey = PackageManagersContext.VCS_PREFIX + PackageManagersContext.GIT_SSH_KEY;
        String sshFileContent = readFileByKey(params, sshFileKey);
        if (StringUtils.isNotBlank(sshFileContent)) {
            taskConfigMap.put(sshFileKey, sshFileContent);
        } else {
            if (previousTaskDefinition != null) {
                taskConfigMap.put(sshFileKey, previousTaskDefinition.getConfiguration().get(sshFileKey));
            }
        }
        return taskConfigMap;
    }

    private String getVcsTypeName(final String vcsType) {
        return i18nResolver.getText("artifactory.vcs.type." + StringUtils.lowerCase(vcsType));
    }

    private String getAuthTypeName(final String authType) {
        return i18nResolver.getText("artifactory.vcs.git.authenticationType." + StringUtils.lowerCase(authType));
    }

    public void setI18nResolver(final I18nResolver i18nResolver) {
        this.i18nResolver = i18nResolver;
    }

    public static void populateDefaultEnvVarsExcludePatternsInBuildContext(@NotNull Map<String, Object> context) {
        String envVarsExcludePatterns = (String) context.get(PackageManagersContext.ENV_VARS_EXCLUDE_PATTERNS);
        if (envVarsExcludePatterns == null) {
            context.put(PackageManagersContext.ENV_VARS_EXCLUDE_PATTERNS, PackageManagersContext.ENV_VARS_TO_EXCLUDE);
        }
    }

    public static void populateDefaultBuildNameNumberInBuildContext(@NotNull Map<String, Object> context) {
        String buildName = (String) context.get(ArtifactoryBuildContext.BUILD_NAME);
        if (buildName == null) {
            context.put(ArtifactoryBuildContext.BUILD_NAME, ArtifactoryBuildContext.DEFAULT_BUILD_NAME);
        }
        String buildNumber = (String) context.get(ArtifactoryBuildContext.BUILD_NUMBER);
        if (buildNumber == null) {
            context.put(ArtifactoryBuildContext.BUILD_NUMBER, ArtifactoryBuildContext.DEFAULT_BUILD_NUMBER);
        }
    }
}
