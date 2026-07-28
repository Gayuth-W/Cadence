# Cadence Service Methods

### src\main\java\com\cadence\flagservice\CadenceApplication.java
- public static void main(String[] args)

### src\main\java\com\cadence\flagservice\alert\AlertService.java
- public AlertService(WebClient cadenceWebClient, CadenceProperties properties)
- public void automaticRollback(FeatureFlag flag,
                                  int previousPercentage,
                                  List<String> breachingMetrics,
                                  WindowStats candidate,
                                  WindowStats lastStableBaseline)
- public void canaryBlocked(FeatureFlag flag, String reason)
- public void rolloutCompleted(FeatureFlag flag)
- private void send(String text)
- public static String describeBreaches(Map<String, double[]> observedVsThreshold)

### src\main\java\com\cadence\flagservice\audit\controller\AuditController.java
- public AuditController(AuditRecordRepository repository)
- public Page<AuditRecordResponse> list(@RequestParam(defaultValue = "0")
- public Page<AuditRecordResponse> forFlag(@PathVariable UUID flagId,
                                             @RequestParam(defaultValue = "0")
- public List<AuditRecordResponse> rollbacks(@PathVariable UUID flagId)

### src\main\java\com\cadence\flagservice\audit\domain\AuditRecord.java
- protected AuditRecord()
- private AuditRecord(Builder builder)
- public static Builder builder(AuditAction action, String actor)
- public UUID getId()
- public UUID getFlagId()
- public String getFlagKey()
- public AuditAction getAction()
- public String getActor()
- public String getOldValue()
- public String getNewValue()
- public String getReason()
- public Instant getCreatedAt()
- private Builder(AuditAction action, String actor)
- public Builder flag(UUID flagId, String flagKey)
- public Builder oldValue(String oldValue)
- public Builder newValue(String newValue)
- public Builder reason(String reason)
- public Builder metadata(Map<String, Object> metadata)
- public AuditRecord build()

### src\main\java\com\cadence\flagservice\audit\dto\AuditRecordResponse.java
- public record AuditRecordResponse(
        UUID id,
        UUID flagId,
        String flagKey,
        AuditAction action,
        String actor,
        String oldValue,
        String newValue,
        String reason,
        Map<String, Object> metadata,
        Instant createdAt
)
- public static AuditRecordResponse from(AuditRecord r)

### src\main\java\com\cadence\flagservice\audit\repository\AuditRecordRepository.java
- Page<AuditRecord> findByFlagIdOrderByCreatedAtDesc(UUID flagId, Pageable pageable)
- Page<AuditRecord> findByActorOrderByCreatedAtDesc(String actor, Pageable pageable)
- Page<AuditRecord> findAllByOrderByCreatedAtDesc(Pageable pageable)
- List<AuditRecord> findRollbacks(@Param("flagId")
- List<AuditRecord> findByFlagIdAndActionAndCreatedAtAfterOrderByCreatedAtDesc(
                        UUID flagId, AuditAction action, Instant after)

### src\main\java\com\cadence\flagservice\audit\service\FlagAuditService.java
- public FlagAuditService(AuditRecordRepository repository, CurrentActor currentActor)
- public AuditRecord record(AuditAction action, UUID flagId, String flagKey,
                              String oldValue, String newValue, String reason)
- public AuditRecord record(AuditAction action, UUID flagId, String flagKey,
                              String oldValue, String newValue, String reason,
                              Map<String, Object> metadata)
- public AuditRecord recordSystem(AuditAction action, UUID flagId, String flagKey,
                                    String oldValue, String newValue, String reason,
                                    Map<String, Object> metadata)

### src\main\java\com\cadence\flagservice\common\ApiError.java
- public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors
)
- public static ApiError of(int status, String error, String message, String path)

### src\main\java\com\cadence\flagservice\common\ConflictException.java
- public ConflictException(String message)

### src\main\java\com\cadence\flagservice\common\GlobalExceptionHandler.java
- public ResponseEntity<ApiError> handleNotFound(NotFoundException e, HttpServletRequest req)
- public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException e, HttpServletRequest req)
- public ResponseEntity<ApiError> handleConflict(ConflictException e, HttpServletRequest req)
- public ResponseEntity<ApiError> handleOptimisticLock(Exception e, HttpServletRequest req)
- public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req)
- public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException e, HttpServletRequest req)
- public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e, HttpServletRequest req)
- public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest req)
- public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest req)

### src\main\java\com\cadence\flagservice\common\JsonUtils.java
- public JsonUtils(@Qualifier("internalObjectMapper")
- public String toJson(Object value)

### src\main\java\com\cadence\flagservice\common\NotFoundException.java
- public NotFoundException(String message)
- public static NotFoundException flag(Object id)

### src\main\java\com\cadence\flagservice\config\AsyncConfig.java
- public AsyncConfig(CadenceProperties properties)
- public Executor ingestionExecutor()
- public Executor getAsyncExecutor()
- public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler()
- public SimpleAsyncTaskScheduler taskScheduler()
- public void configureTasks(ScheduledTaskRegistrar registrar)

### src\main\java\com\cadence\flagservice\config\CadenceProperties.java
- public String getSecret()
- public void setSecret(String secret)
- public Duration getExpiration()
- public void setExpiration(Duration expiration)
- public String getIssuer()
- public void setIssuer(String issuer)
- public boolean isEnabled()
- public void setEnabled(boolean enabled)
- public Duration getCheckInterval()
- public void setCheckInterval(Duration checkInterval)
- public int getConsecutiveBreaches()
- public void setConsecutiveBreaches(int v)
- public int getMinSamples()
- public void setMinSamples(int v)
- public Duration getCooldown()
- public void setCooldown(Duration cooldown)
- public boolean isEnabled()
- public void setEnabled(boolean enabled)
- public double getAlpha()
- public void setAlpha(double alpha)
- public int getMinSamples()
- public void setMinSamples(int minSamples)
- public WindowType getWindow()
- public void setWindow(WindowType window)
- public int getMaxSamples()
- public void setMaxSamples(int maxSamples)
- public Duration getSnapshotInterval()
- public void setSnapshotInterval(Duration v)
- public int getMaxWindowSize()
- public void setMaxWindowSize(int v)
- public int getIngestionConcurrency()
- public void setIngestionConcurrency(int v)
- public boolean isEnabled()
- public void setEnabled(boolean enabled)
- public String getSlackWebhookUrl()
- public void setSlackWebhookUrl(String v)
- public boolean isEnabled()
- public void setEnabled(boolean enabled)
- public String getPrometheusBaseUrl()
- public void setPrometheusBaseUrl(String v)
- public Duration getTimeout()
- public void setTimeout(Duration timeout)
- public String getEnvironment()
- public void setEnvironment(String environment)
- public Jwt getJwt()
- public Rollback getRollback()
- public Canary getCanary()
- public Metrics getMetrics()
- public Alerting getAlerting()
- public ExternalMetrics getExternalMetrics()

### src\main\java\com\cadence\flagservice\config\JacksonConfig.java
- public Jackson2ObjectMapperBuilderCustomizer cadenceJacksonCustomizer()
- public ObjectMapper internalObjectMapper()

### src\main\java\com\cadence\flagservice\config\OpenApiConfig.java
- public OpenAPI cadenceOpenApi()

### src\main\java\com\cadence\flagservice\config\RedisConfig.java
- public ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory factory)

### src\main\java\com\cadence\flagservice\config\WebClientConfig.java
- public WebClient cadenceWebClient(CadenceProperties properties)

### src\main\java\com\cadence\flagservice\flag\controller\FlagController.java
- public FlagController(FeatureFlagService flagService, FlagMapper mapper)
- public List<FlagResponse> list()
- public FlagResponse get(@PathVariable UUID id)
- public FlagResponse getByKey(@PathVariable String key)
- public FlagResponse create(@Valid @RequestBody CreateFlagRequest request)
- public FlagResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateFlagRequest request)
- public void delete(@PathVariable UUID id)
- public FlagResponse rollout(@PathVariable UUID id, @Valid @RequestBody RolloutRequest request)
- public FlagResponse pause(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request)
- public FlagResponse resume(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request)
- public FlagResponse rollback(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request)
- public FlagResponse reset(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request)
- public FlagResponse shadow(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request)

### src\main\java\com\cadence\flagservice\flag\controller\SdkController.java
- public SdkController(FeatureFlagService flagService,
            MetricIngestionService ingestionService,
            CadenceProperties properties)
- public List<FlagDefinition> flags(HttpServletRequest request)
- public EvaluationResult evaluate(@Valid @RequestBody EvaluationRequest request, HttpServletRequest http)
- public ResponseEntity<Void> events(@RequestBody EventBatch batch)
- private String environmentOf(HttpServletRequest request)

### src\main\java\com\cadence\flagservice\flag\domain\FeatureFlag.java
- protected FeatureFlag()
- public FeatureFlag(String key, String description, String environment, String createdBy)
- void touch()
- public FlagDefinition toDefinition()
- public UUID getId()
- public String getKey()
- public String getDescription()
- public void setDescription(String description)
- public FlagState getState()
- public void setState(FlagState state)
- public int getRolloutPercentage()
- public void setRolloutPercentage(int rolloutPercentage)
- public String getEnvironment()
- public void setEnvironment(String environment)
- public void setBaselineConfig(Map<String, Object> c)
- public void setCandidateConfig(Map<String, Object> c)
- public TargetingRules getTargetingRules()
- public void setTargetingRules(TargetingRules r)
- public List<HealthMetricSpec> getHealthMetrics()
- public void setHealthMetrics(List<HealthMetricSpec> m)
- public RollbackTrigger getRollbackTrigger()
- public void setRollbackTrigger(RollbackTrigger t)
- public Integer getPercentageBeforeRollback()
- public void setPercentageBeforeRollback(Integer p)
- public long getVersion()
- public String getCreatedBy()
- public Instant getCreatedAt()
- public Instant getUpdatedAt()

### src\main\java\com\cadence\flagservice\flag\dto\CreateFlagRequest.java
- public record CreateFlagRequest(
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9._-]{3,200}$",
                message = "Flag key must be 3-200 chars of letters, digits, dot, underscore or hyphen")

### src\main\java\com\cadence\flagservice\flag\dto\FlagResponse.java
- public record FlagResponse(
        UUID id,
        String key,
        String description,
        FlagState state,
        int rolloutPercentage,
        String environment,
        Map<String, Object> baselineConfig,
        Map<String, Object> candidateConfig,
        TargetingRules targetingRules,
        List<HealthMetricSpec> healthMetrics,
        RollbackTrigger rollbackTrigger,
        long version,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
)
- public static FlagResponse from(FeatureFlag f)

### src\main\java\com\cadence\flagservice\flag\dto\ReasonRequest.java
- public record ReasonRequest(
        @NotBlank(message = "A reason is required")

### src\main\java\com\cadence\flagservice\flag\dto\RolloutRequest.java
- public record RolloutRequest(
        @Min(0)

### src\main\java\com\cadence\flagservice\flag\dto\UpdateFlagRequest.java
- public record UpdateFlagRequest(
        String description,
        Map<String, Object> baselineConfig,
        Map<String, Object> candidateConfig,
        TargetingRules targetingRules,
        List<HealthMetricSpec> healthMetrics,
        RollbackTrigger rollbackTrigger,
        String reason
)

### src\main\java\com\cadence\flagservice\flag\mapper\FlagMapper.java
- public FlagResponse toResponse(FeatureFlag flag)
- public List<FlagResponse> toResponses(List<FeatureFlag> flags)
- public FlagDefinition toDefinition(FeatureFlag flag)
- public List<FlagDefinition> toDefinitions(List<FeatureFlag> flags)

### src\main\java\com\cadence\flagservice\flag\repository\FeatureFlagRepository.java
- Optional<FeatureFlag> findByKeyAndEnvironment(String key, String environment)
- boolean existsByKeyAndEnvironment(String key, String environment)
- List<FeatureFlag> findAllByEnvironment(String environment)
- List<FeatureFlag> findAllByEnvironmentAndStateIn(String environment, Collection<FlagState> states)
- Optional<FeatureFlag> findByIdForUpdate(@Param("id")

### src\main\java\com\cadence\flagservice\flag\service\FeatureFlagService.java
- public FeatureFlagService(FeatureFlagRepository repository,
            FlagAuditService auditService,
            CadenceProperties properties,
            CurrentActor currentActor,
            RolloutBroadcaster broadcaster,
            JsonUtils json,
            MetricWindowService windowService,
            RollbackGuardState guardState)
- public List<FeatureFlag> findAll()
- public FeatureFlag findById(UUID id)
- public FeatureFlag findByKey(String key)
- public FeatureFlag get(UUID id)
- public List<FeatureFlag> findActiveRollouts()
- public List<FlagDefinition> definitionsFor(String environment)
- public EvaluationResult evaluate(String flagKey, String environment, UserContext ctx)
- public FeatureFlag create(CreateFlagRequest request)
- throw new ConflictException("Flag already exists in this environment: " + request.key()
- public FeatureFlag update(UUID id, UpdateFlagRequest request)
- public void delete(UUID id)
- throw new ConflictException(
                    "Flag '%s' is live at %d%%. Roll it back before deleting."
                            .formatted(flag.getKey()
- public FeatureFlag setRolloutPercentage(UUID id, int percentage, String reason)
- public FeatureFlag setRolloutPercentageAsSystem(UUID id, int percentage, String reason,
            Map<String, Object> evidence)
- private FeatureFlag applyRolloutPercentage(UUID id, int percentage, String reason, boolean system)
- throw new IllegalArgumentException("Rollout percentage must be between 0 and 100")
- throw new ConflictException(
                    "Flag '%s' was rolled back. An ADMIN must resume it before the rollout can advance."
                            .formatted(flag.getKey()
- private void applyPercentageToEntity(FeatureFlag flag, int percentage)
- public FeatureFlag pause(UUID id, String reason)
- throw new ConflictException("Only a ROLLING_OUT flag can be paused; '%s' is %s"
                    .formatted(flag.getKey()
- public FeatureFlag pauseAsSystem(UUID id, String reason, Map<String, Object> evidence)
- public FeatureFlag resume(UUID id, String reason)
- throw new ConflictException("Only a PAUSED flag can be resumed; '%s' is %s"
                    .formatted(flag.getKey()
- public FeatureFlag rollback(UUID id, String reason)
- public FeatureFlag rollbackAsSystem(UUID id, String reason, Map<String, Object> evidence)
- private void applyRollbackToEntity(FeatureFlag flag)
- public FeatureFlag reset(UUID id, String reason)
- throw new ConflictException("Only a ROLLED_BACK flag needs resetting; '%s' is %s"
                    .formatted(flag.getKey()
- private void clearReleaseHealthState(FeatureFlag flag)
- public FeatureFlag enableShadow(UUID id, String reason)
- throw new ConflictException(
                    "Flag '%s' is already serving the candidate to users; shadow mode is a pre-rollout step"
                            .formatted(flag.getKey()

### src\main\java\com\cadence\flagservice\metrics\controller\MetricController.java
- public MetricController(FeatureFlagService flagService,
                            MetricWindowService windowService,
                            CanaryAnalysisService canaryService,
                            MetricSnapshotRepository snapshotRepository,
                            AuditRecordRepository auditRepository)
- public CanaryResult canary(@PathVariable UUID flagId)
- public List<MetricSnapshotResponse> snapshots(
            @PathVariable UUID flagId,
            @RequestParam(required = false)
- public ResponseEntity<String> exportCsv(@PathVariable UUID flagId)
- public FlagAnalytics analytics(@PathVariable UUID flagId)
- public ResponseEntity<Void> resetWindows(@PathVariable UUID flagId)

### src\main\java\com\cadence\flagservice\metrics\domain\MetricSnapshot.java
- protected MetricSnapshot()
- private MetricSnapshot(Builder b)
- public static Builder builder()
- public UUID getId()
- public UUID getFlagId()
- public String getFlagKey()
- public String getVariantKey()
- public WindowType getWindowType()
- public long getSampleCount()
- public long getErrorCount()
- public double getErrorRate()
- public double getMeanLatencyMs()
- public double getStdDevLatencyMs()
- public double getP50LatencyMs()
- public double getP95LatencyMs()
- public double getP99LatencyMs()
- public double getThroughputPerMinute()
- public Trend getTrend()
- public int getRolloutPercentage()
- public Instant getCapturedAt()
- public Builder flag(UUID flagId, String flagKey)
- public Builder variantKey(String v)
- public Builder windowType(WindowType w)
- public Builder sampleCount(long v)
- public Builder errorCount(long v)
- public Builder errorRate(double v)
- public Builder meanLatencyMs(double v)
- public Builder stdDevLatencyMs(double v)
- public Builder p50LatencyMs(double v)
- public Builder p95LatencyMs(double v)
- public Builder p99LatencyMs(double v)
- public Builder throughputPerMinute(double v)
- public Builder trend(Trend t)
- public Builder customMetrics(Map<String, Double> m)
- public Builder rolloutPercentage(int p)
- public MetricSnapshot build()

### src\main\java\com\cadence\flagservice\metrics\dto\FlagAnalytics.java
- public record FlagAnalytics(
        UUID flagId,
        String flagKey,
        int rollbackCount,
        int automaticRollbacks,
        List<String> rollbackCauses,
        Instant firstRolloutAt,
        Instant completedAt,
        String timeToFullRollout,
        Map<String, Integer> usersAffectedByStage
)

### src\main\java\com\cadence\flagservice\metrics\dto\MetricSnapshotResponse.java
- public record MetricSnapshotResponse(
        UUID id,
        UUID flagId,
        String flagKey,
        String variantKey,
        WindowType windowType,
        long sampleCount,
        long errorCount,
        double errorRate,
        double meanLatencyMs,
        double stdDevLatencyMs,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double throughputPerMinute,
        MetricSnapshot.Trend trend,
        Map<String, Double> customMetrics,
        int rolloutPercentage,
        Instant capturedAt
)
- public static MetricSnapshotResponse from(MetricSnapshot s)

### src\main\java\com\cadence\flagservice\metrics\external\PrometheusMetricClient.java
- public PrometheusMetricClient(WebClient cadenceWebClient, CadenceProperties properties)
- public Optional<Double> instantQuery(String promQl)

### src\main\java\com\cadence\flagservice\metrics\model\CanaryResult.java
- public record CanaryResult(
        boolean passed,
        boolean abstained,
        double uStatistic,
        double pValue,
        int candidateSamples,
        int baselineSamples,
        double candidateMedian,
        double baselineMedian,
        String reason
)
- public static CanaryResult abstain(int candidateSamples, int baselineSamples, String reason)

### src\main\java\com\cadence\flagservice\metrics\model\WindowStats.java
- public record WindowStats(
        String variantKey,
        WindowType window,
        long sampleCount,
        long errorCount,
        double errorRate,
        double meanLatencyMs,
        double stdDevLatencyMs,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double throughputPerMinute,
        Map<String, Double> customMetrics
)
- public static WindowStats empty(String variantKey, WindowType window)
- public boolean isEmpty()
- public double valueOf(MetricKind kind, String customName)

### src\main\java\com\cadence\flagservice\metrics\repository\MetricSnapshotRepository.java
- List<MetricSnapshot> findByFlagIdAndCapturedAtBetweenOrderByCapturedAtAsc(
            UUID flagId, Instant from, Instant to)
- List<MetricSnapshot> findByFlagIdAndVariantKeyOrderByCapturedAtDesc(UUID flagId, String variantKey)
- MetricSnapshot findFirstByFlagIdAndVariantKeyOrderByCapturedAtDesc(UUID flagId, String variantKey)
- void deleteByCapturedAtBefore(Instant cutoff)

### src\main\java\com\cadence\flagservice\metrics\service\CanaryAnalysisService.java
- public CanaryAnalysisService(MetricWindowService windowService, CadenceProperties properties)
- public CanaryResult analyse(FeatureFlag flag)
- private static double[] toArray(List<Double> values)
- private static double median(List<Double> ascending)

### src\main\java\com\cadence\flagservice\metrics\service\MetricIngestionService.java
- public MetricIngestionService(MetricWindowService windowService, MeterRegistry meterRegistry)
- public void ingestAsync(EventBatch batch)
- public void ingest(EventBatch batch)
- private boolean isPlausible(OutcomeEvent event)

### src\main\java\com\cadence\flagservice\metrics\service\MetricSnapshotService.java
- public MetricSnapshotService(FeatureFlagService flagService,
                                 MetricWindowService windowService,
                                 MetricSnapshotRepository repository,
                                 RolloutBroadcaster broadcaster,
                                 CadenceProperties properties)
- public void capture()
- private void captureFlag(FeatureFlag flag)
- private static double compositeScore(WindowStats stats)
- public void prune()

### src\main\java\com\cadence\flagservice\metrics\service\MetricWindowService.java
- public MetricWindowService(ReactiveStringRedisTemplate redis, CadenceProperties properties)
- public Mono<Void> record(OutcomeEvent event)
- public Mono<Void> evict(String flagKey, String variantKey)
- private Mono<Void> evictWindow(String flagKey, String variantKey, WindowType window)
- ? evictByCount(idx, window)
- private Mono<List<String>> evictByCount(String idxKey, WindowType window)
- private Mono<List<String>> evictByAge(String idxKey, WindowType window)
- public WindowStats stats(String flagKey, String variantKey, WindowType window)
- public Mono<WindowStats> statsReactive(String flagKey, String variantKey, WindowType window)
- ? throughputFromSpan(sortedLatencies.size()
- public List<Double> latencySamples(String flagKey, String variantKey, WindowType window, int limit)
- private Mono<Set<String>> customNames(String flagKey, String variantKey)
- public Mono<Void> reset(String flagKey)
- static double percentile(List<Double> ascending, double p)
- private double throughputFromSpan(int samples, WindowType window)
- private Duration ttlFor(WindowType window)
- private static String newMember(long timestampMillis)
- private String prefix()
- private String base(String flagKey, String variantKey)
- String idxKey(String flagKey, String variantKey, WindowType w)
- String latKey(String flagKey, String variantKey, WindowType w)
- String errKey(String flagKey, String variantKey, WindowType w)
- String cstKey(String flagKey, String variantKey, String name, WindowType w)
- String cstNamesKey(String flagKey, String variantKey)

### src\main\java\com\cadence\flagservice\metrics\service\RollbackGuardState.java
- public RollbackGuardState(StringRedisTemplate redis, CadenceProperties properties)
- public long recordBreach(UUID flagId, Duration ttl)
- public void clearBreaches(UUID flagId)
- public boolean isInCooldown(UUID flagId)
- public void enterCooldown(UUID flagId, Duration duration)
- public void clear(UUID flagId)
- String breachKey(UUID flagId)
- String cooldownKey(UUID flagId)

### src\main\java\com\cadence\flagservice\metrics\service\RollbackWatcherService.java
- public RollbackWatcherService(FeatureFlagService flagService,
                                  MetricWindowService windowService,
                                  AlertService alertService,
                                  RollbackGuardState guardState,
                                  CadenceProperties properties)
- public void watch()
- private void evaluate(FeatureFlag flag)
- private void fireRollback(FeatureFlag flag, List<String> breaches,
                              Map<String, Object> evidence, WindowStats candidateStats)
- private Duration breachCounterTtl(RollbackTrigger trigger)

### src\main\java\com\cadence\flagservice\rollout\controller\RolloutScheduleController.java
- public RolloutScheduleController(RolloutSchedulerService schedulerService)
- public ScheduleResponse create(@Valid @RequestBody CreateScheduleRequest request)
- public ScheduleResponse get(@PathVariable UUID id)
- public List<ScheduleResponse> byFlag(@RequestParam UUID flagId)
- public ScheduleResponse start(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request)
- public ScheduleResponse pause(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request)
- public ScheduleResponse resume(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request)
- public CanaryResult advance(@PathVariable UUID id)

### src\main\java\com\cadence\flagservice\rollout\domain\RolloutSchedule.java
- protected RolloutSchedule()
- public RolloutSchedule(UUID flagId, String flagKey, List<RolloutStage> stages, String createdBy)
- public RolloutStage currentStage()
- public boolean hasNextStage()
- public RolloutStage nextStage()
- public UUID getId()
- public UUID getFlagId()
- public String getFlagKey()
- public List<RolloutStage> getStages()
- public void setStages(List<RolloutStage> stages)
- public int getCurrentStageIndex()
- public void setCurrentStageIndex(int i)
- public RolloutStatus getStatus()
- public void setStatus(RolloutStatus status)
- public Instant getStartedAt()
- public void setStartedAt(Instant startedAt)
- public Instant getNextTransitionAt()
- public void setNextTransitionAt(Instant at)
- public Instant getCompletedAt()
- public void setCompletedAt(Instant at)
- public String getCreatedBy()
- public Instant getCreatedAt()
- public long getVersion()

### src\main\java\com\cadence\flagservice\rollout\domain\RolloutStage.java
- public record RolloutStage(int percentage, long durationMinutes)
- throw new IllegalArgumentException("Stage percentage must be between 0 and 100")
- throw new IllegalArgumentException("Stage duration must not be negative")

### src\main\java\com\cadence\flagservice\rollout\dto\CreateScheduleRequest.java
- public record CreateScheduleRequest(
        @NotNull UUID flagId,
        @NotEmpty List<RolloutStage> stages
)

### src\main\java\com\cadence\flagservice\rollout\dto\ScheduleResponse.java
- public record ScheduleResponse(
        UUID id,
        UUID flagId,
        String flagKey,
        List<RolloutStage> stages,
        int currentStageIndex,
        Integer currentPercentage,
        RolloutStatus status,
        Instant startedAt,
        Instant nextTransitionAt,
        Instant completedAt,
        String createdBy,
        Instant createdAt
)
- public static ScheduleResponse from(RolloutSchedule s)

### src\main\java\com\cadence\flagservice\rollout\repository\RolloutScheduleRepository.java
- List<RolloutSchedule> findByFlagIdOrderByCreatedAtDesc(UUID flagId)
- Optional<RolloutSchedule> findFirstByFlagIdAndStatusIn(UUID flagId, List<RolloutStatus> statuses)
- List<RolloutSchedule> findByStatusAndNextTransitionAtBefore(RolloutStatus status, Instant now)

### src\main\java\com\cadence\flagservice\rollout\service\RolloutReconciler.java
- public RolloutReconciler(RolloutSchedulerService schedulerService)
- public void tick()

### src\main\java\com\cadence\flagservice\rollout\service\RolloutSchedulerService.java
- public RolloutSchedulerService(RolloutScheduleRepository repository,
                                   FeatureFlagService flagService,
                                   CanaryAnalysisService canaryService,
                                   FlagAuditService auditService,
                                   AlertService alertService,
                                   RolloutBroadcaster broadcaster,
                                   CurrentActor currentActor)
- public RolloutSchedule create(UUID flagId, List<RolloutStage> stages)
- throw new IllegalArgumentException("A schedule needs at least one stage")
- throw new IllegalArgumentException(
                        "Stage percentages must strictly increase; stage %d (%d%%)
- throw new ConflictException("Flag '%s' already has an active schedule".formatted(flag.getKey()
- public RolloutSchedule start(UUID scheduleId, String reason)
- throw new ConflictException("Schedule is %s, not PENDING".formatted(schedule.getStatus()
- throw new ConflictException("Flag '%s' was rolled back; reset it before starting a schedule"
                    .formatted(flag.getKey()
- public RolloutSchedule pause(UUID scheduleId, String reason)
- throw new ConflictException("Only a RUNNING schedule can be paused")
- public RolloutSchedule resume(UUID scheduleId, String reason)
- throw new ConflictException("Only a PAUSED schedule can be resumed")
- public CanaryResult advanceNow(UUID scheduleId)
- throw new ConflictException("Only a RUNNING schedule can be advanced")
- public RolloutSchedule find(UUID scheduleId)
- public List<RolloutSchedule> findByFlag(UUID flagId)
- public void reconcileDueTransitions()
- private CanaryResult attemptTransition(RolloutSchedule schedule)
- private void blockOnCanary(RolloutSchedule schedule, FeatureFlag flag, CanaryResult canary)
- private CanaryResult complete(RolloutSchedule schedule, FeatureFlag flag)
- private RolloutSchedule get(UUID id)

### src\main\java\com\cadence\flagservice\security\ApiKeyAuthenticationFilter.java
- public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService)
- protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException
- private RequestAttributes()

### src\main\java\com\cadence\flagservice\security\CurrentActor.java
- public String name()
- public boolean isHuman()

### src\main\java\com\cadence\flagservice\security\JwtAuthenticationFilter.java
- public JwtAuthenticationFilter(JwtService jwtService)
- protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException
- private String extractToken(HttpServletRequest request)

### src\main\java\com\cadence\flagservice\security\RestAccessDeniedHandler.java
- public RestAccessDeniedHandler(ObjectMapper mapper)
- public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException e) throws IOException

### src\main\java\com\cadence\flagservice\security\RestAuthenticationEntryPoint.java
- public RestAuthenticationEntryPoint(ObjectMapper mapper)
- public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException

### src\main\java\com\cadence\flagservice\security\SecurityConfig.java
- public SecurityConfig(JwtAuthenticationFilter jwtFilter,
            ApiKeyAuthenticationFilter apiKeyFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler)
- public SecurityFilterChain sdkFilterChain(HttpSecurity http) throws Exception
- public SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception
- public PasswordEncoder passwordEncoder()
- public DaoAuthenticationProvider authenticationProvider(UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder)
- public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception
- public CorsConfigurationSource corsConfigurationSource()

### src\main\java\com\cadence\flagservice\security\controller\ApiKeyController.java
- public ApiKeyController(ApiKeyService apiKeyService, CadenceProperties properties)
- public ApiKeyResponse create(@Valid @RequestBody CreateApiKeyRequest request,
                                 Authentication authentication)
- public List<ApiKeyResponse> list(@RequestParam(required = false)
- public void revoke(@PathVariable UUID id)

### src\main\java\com\cadence\flagservice\security\controller\AuthController.java
- public AuthController(AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          UserAccountRepository userRepository)
- synchronized boolean tryConsume()
- public LoginResponse login(jakarta.servlet.http.HttpServletRequest httpRequest, @Valid @RequestBody LoginRequest request)
- public ResponseEntity<UserResponse> me(Authentication authentication)
- public Set<String> roles(Authentication authentication)

### src\main\java\com\cadence\flagservice\security\controller\UserController.java
- public UserController(UserAccountRepository userRepository, PasswordEncoder passwordEncoder)
- public List<UserResponse> list()
- public UserResponse create(@Valid @RequestBody CreateUserRequest request)
- throw new ConflictException("Username already exists: " + request.username()
- public void delete(@PathVariable UUID id, Authentication authentication)
- throw new ConflictException("You cannot delete the account you are authenticated as")

### src\main\java\com\cadence\flagservice\security\domain\ApiKey.java
- protected ApiKey()
- public ApiKey(String name, String environment, String keyPrefix, String keyHash,
                  Set<ApiKeyScope> scopes, String createdBy)
- public UUID getId()
- public String getName()
- public String getEnvironment()
- public String getKeyPrefix()
- public String getKeyHash()
- public Set<ApiKeyScope> getScopes()
- public boolean isActive()
- public void setActive(boolean active)
- public String getCreatedBy()
- public Instant getCreatedAt()
- public Instant getLastUsedAt()
- public void setLastUsedAt(Instant lastUsedAt)

### src\main\java\com\cadence\flagservice\security\domain\ApiKeyScope.java
- public String authority()

### src\main\java\com\cadence\flagservice\security\domain\Role.java
- public String authority()

### src\main\java\com\cadence\flagservice\security\domain\UserAccount.java
- protected UserAccount()
- public UserAccount(String username, String passwordHash, Set<Role> roles)
- public UUID getId()
- public String getUsername()
- public String getPasswordHash()
- public void setPasswordHash(String passwordHash)
- public boolean isEnabled()
- public void setEnabled(boolean enabled)
- public Set<Role> getRoles()
- public void setRoles(Set<Role> roles)
- public Instant getCreatedAt()

### src\main\java\com\cadence\flagservice\security\dto\ApiKeyResponse.java
- public record ApiKeyResponse(
        UUID id,
        String name,
        String environment,
        String keyPrefix,
        Set<ApiKeyScope> scopes,
        boolean active,
        String createdBy,
        Instant createdAt,
        Instant lastUsedAt,
        String plaintextKey
)
- public static ApiKeyResponse from(ApiKey key)
- public static ApiKeyResponse of(ApiKey key, String plaintextKey)

### src\main\java\com\cadence\flagservice\security\dto\CreateApiKeyRequest.java
- public record CreateApiKeyRequest(
        @NotBlank String name,
        @NotBlank String environment,
        @NotEmpty Set<ApiKeyScope> scopes
)

### src\main\java\com\cadence\flagservice\security\dto\CreateUserRequest.java
- public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 100)

### src\main\java\com\cadence\flagservice\security\dto\LoginRequest.java
- public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
)

### src\main\java\com\cadence\flagservice\security\dto\LoginResponse.java
- public record LoginResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        String username,
        Set<String> roles
)

### src\main\java\com\cadence\flagservice\security\dto\UserResponse.java
- public record UserResponse(UUID id, String username, Set<Role> roles, boolean enabled, Instant createdAt)
- public static UserResponse from(UserAccount account)

### src\main\java\com\cadence\flagservice\security\repository\ApiKeyRepository.java
- Optional<ApiKey> findByKeyPrefixAndActiveTrue(String keyPrefix)
- List<ApiKey> findAllByEnvironment(String environment)

### src\main\java\com\cadence\flagservice\security\repository\UserAccountRepository.java
- Optional<UserAccount> findByUsername(String username)
- boolean existsByUsername(String username)

### src\main\java\com\cadence\flagservice\security\service\ApiKeyService.java
- public ApiKeyService(ApiKeyRepository apiKeyRepository)
- public record IssuedKey(ApiKey record, String plaintext)
- public IssuedKey issue(String name, String environment, Set<ApiKeyScope> scopes, String createdBy)
- public Optional<ApiKey> verify(String presented)
- public List<ApiKey> list(String environment)
- public void revoke(UUID id)
- private static String sha256(String input)
- throw new IllegalStateException("SHA-256 unavailable", e)

### src\main\java\com\cadence\flagservice\security\service\CustomUserDetailsService.java
- public CustomUserDetailsService(UserAccountRepository userRepository)
- public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException

### src\main\java\com\cadence\flagservice\security\service\JwtService.java
- public JwtService(CadenceProperties properties)
- void init()
- throw new IllegalStateException(
                    "cadence.jwt.secret is not set. Refusing to start: an unsigned or default-signed "
                    + "control plane lets anyone mint an ADMIN token and force a rollback.")
- throw new IllegalStateException(
                    "cadence.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256; got "
                    + keyBytes.length)
- public String generateToken(String username, Set<Role> roles)
- public Instant expiryOf(String token)
- public String usernameOf(String token)
- public List<String> rolesOf(String token)
- public Claims parse(String token)
- public boolean isValid(String token)

### src\main\java\com\cadence\flagservice\websocket\RolloutBroadcaster.java
- public RolloutBroadcaster(SimpMessagingTemplate messagingTemplate)
- public void flagChanged(UUID flagId, String flagKey, String event, Map<String, Object> payload)
- public void metrics(UUID flagId, Object snapshot)
- private void send(String destination, Object payload)

### src\main\java\com\cadence\flagservice\websocket\WebSocketConfig.java
- public WebSocketConfig(com.cadence.flagservice.security.service.JwtService jwtService)
- public void configureMessageBroker(MessageBrokerRegistry registry)
- public void registerStompEndpoints(StompEndpointRegistry registry)
- public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration)

### src\test\java\com\cadence\flagservice\AbstractIntegrationTest.java
- static void redisProperties(DynamicPropertyRegistry registry)
- protected String json(Object value) throws Exception
- protected String login(String username, String password) throws Exception
- protected String createFlag(String adminToken, String key) throws Exception
- protected String createFlag(String adminToken, String key, double maxErrorRate, double maxP95Ms,
                                int consecutiveBreaches, int minSamples) throws Exception
- protected void rollout(String token, String flagId, int percentage) throws Exception
- protected String tokenSignedWithWrongKey()
- void ensureContainersRunning()

### src\test\java\com\cadence\flagservice\AutomaticRollbackIntegrationTest.java
- void degradedCandidateIsAutomaticallyRolledBack() throws Exception
- void healthyCandidateSurvives() throws Exception
- void belowMinSamplesTheWatcherAbstains() throws Exception
- void breachCounterResetsOnRecovery() throws Exception
- void resetClearsStaleWindows() throws Exception
- void resetClearsCooldownSoTheWatcherKeepsGuarding() throws Exception
- private void reset(String token, String flagId) throws Exception
- private FlagState state(String flagId)

### src\test\java\com\cadence\flagservice\CanaryGateIntegrationTest.java
- void significantlySlowerCandidateIsBlocked() throws Exception
- void statisticallyIndistinguishableCandidatePasses() throws Exception
- void significantlyFasterCandidatePasses() throws Exception
- void tooFewSamplesAbstains() throws Exception
- private FeatureFlag seedFlag(String key) throws Exception

### src\test\java\com\cadence\flagservice\SdkDataPlaneIntegrationTest.java
- void anonymousIsRejected() throws Exception
- void invalidKeyIsRejected() throws Exception
- void validKeyReadsFlags() throws Exception
- void validKeyPostsEvents() throws Exception
- void thePlanesDoNotCrossOver() throws Exception
- void serverSideEvaluationMatchesTheSharedEngine() throws Exception

### src\test\java\com\cadence\flagservice\SecurityAndAuditIntegrationTest.java
- void missingTokenIsUnauthorized() throws Exception
- void malformedTokenIsUnauthorized() throws Exception
- void badCredentials() throws Exception
- void viewerIsReadOnly() throws Exception
- void operatorCannotForceRollback() throws Exception
- void rolledBackFlagRefusesToAdvance() throws Exception
- void auditCapturesTheRealActor() throws Exception
- void reasonIsMandatory() throws Exception
- void auditIsReadOnlyOverHttp() throws Exception

### src\test\java\com\cadence\flagservice\StagedRolloutIntegrationTest.java
- void startEntersFirstStageUngated() throws Exception
- void healthyCandidateAdvancesToCompletion() throws Exception
- void regressedCandidateIsBlockedByTheCanaryGate() throws Exception
- void schedulesMustMoveForward() throws Exception
- void viewerCannotAdvance() throws Exception
- private String createSchedule(String token, String flagId) throws Exception
- private void start(String token, String scheduleId) throws Exception
- private int percentage(String flagId)
- private FlagState state(String flagId)

### src\test\java\com\cadence\flagservice\TestSupport.java
- private TestSupport()
- public static EventBatch healthy(String flagKey, VariantName variant, int count, double meanLatencyMs)
- public static EventBatch batch(String flagKey, VariantName variant, int count,
                                   double meanLatencyMs, double errorRate)
