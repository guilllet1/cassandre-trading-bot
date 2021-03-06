package tech.cassandre.trading.bot.configuration;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.ConnectableFlux;
import tech.cassandre.trading.bot.batch.AccountFlux;
import tech.cassandre.trading.bot.batch.OrderFlux;
import tech.cassandre.trading.bot.batch.PositionFlux;
import tech.cassandre.trading.bot.batch.TickerFlux;
import tech.cassandre.trading.bot.batch.TradeFlux;
import tech.cassandre.trading.bot.dto.market.TickerDTO;
import tech.cassandre.trading.bot.dto.position.PositionDTO;
import tech.cassandre.trading.bot.dto.position.PositionRulesDTO;
import tech.cassandre.trading.bot.dto.position.PositionStatusDTO;
import tech.cassandre.trading.bot.dto.trade.OrderDTO;
import tech.cassandre.trading.bot.dto.trade.OrderTypeDTO;
import tech.cassandre.trading.bot.dto.trade.TradeDTO;
import tech.cassandre.trading.bot.dto.user.AccountDTO;
import tech.cassandre.trading.bot.dto.user.UserDTO;
import tech.cassandre.trading.bot.dto.util.CurrencyDTO;
import tech.cassandre.trading.bot.dto.util.CurrencyPairDTO;
import tech.cassandre.trading.bot.repository.PositionRepository;
import tech.cassandre.trading.bot.repository.TradeRepository;
import tech.cassandre.trading.bot.service.PositionService;
import tech.cassandre.trading.bot.service.TradeService;
import tech.cassandre.trading.bot.service.UserService;
import tech.cassandre.trading.bot.service.dry.TradeServiceDryModeImplementation;
import tech.cassandre.trading.bot.service.dry.UserServiceDryModeImplementation;
import tech.cassandre.trading.bot.strategy.CassandreStrategy;
import tech.cassandre.trading.bot.strategy.CassandreStrategyInterface;
import tech.cassandre.trading.bot.strategy.GenericCassandreStrategy;
import tech.cassandre.trading.bot.util.base.BaseConfiguration;
import tech.cassandre.trading.bot.util.exception.ConfigurationException;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StrategyAutoConfiguration configures the strategy.
 */
@Configuration
public class StrategyAutoConfiguration extends BaseConfiguration {

    /** Application context. */
    private final ApplicationContext applicationContext;

    /** Trade service. */
    private final TradeService tradeService;

    /** Position service. */
    private final PositionService positionService;

    /** User service. */
    private final UserService userService;

    /** Account flux. */
    private final AccountFlux accountFlux;

    /** Ticker flux. */
    private final TickerFlux tickerFlux;

    /** Order flux. */
    private final OrderFlux orderFlux;

    /** Trade flux. */
    private final TradeFlux tradeFlux;

    /** Position flux. */
    private final PositionFlux positionFlux;

    /** Position repository. */
    private final PositionRepository positionRepository;

    /** Trade repository. */
    private final TradeRepository tradeRepository;

    /**
     * Constructor.
     *
     * @param newApplicationContext application context
     * @param newUserService        user service
     * @param newTradeService       trade service
     * @param newPositionService    position service
     * @param newAccountFlux        account flux
     * @param newTickerFlux         ticker flux
     * @param newOrderFlux          order flux
     * @param newTradeFlux          trade flux
     * @param newPositionFlux       position flux
     * @param newPositionRepository position repository
     * @param newTradeRepository    trade repository
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public StrategyAutoConfiguration(final ApplicationContext newApplicationContext,
                                     final UserService newUserService,
                                     final TradeService newTradeService,
                                     final PositionService newPositionService,
                                     final AccountFlux newAccountFlux,
                                     final TickerFlux newTickerFlux,
                                     final OrderFlux newOrderFlux,
                                     final TradeFlux newTradeFlux,
                                     final PositionFlux newPositionFlux,
                                     final PositionRepository newPositionRepository,
                                     final TradeRepository newTradeRepository) {
        this.applicationContext = newApplicationContext;
        this.userService = newUserService;
        this.tradeService = newTradeService;
        this.positionService = newPositionService;
        this.accountFlux = newAccountFlux;
        this.tickerFlux = newTickerFlux;
        this.orderFlux = newOrderFlux;
        this.tradeFlux = newTradeFlux;
        this.positionFlux = newPositionFlux;
        this.positionRepository = newPositionRepository;
        this.tradeRepository = newTradeRepository;
    }

    /**
     * Search for the strategy and runs it.
     */
    @PostConstruct
    public void configure() {
        // Retrieving all the beans have the annotation @Strategy.
        final Map<String, Object> strategyBeans = applicationContext.getBeansWithAnnotation(CassandreStrategy.class);

        // =============================================================================================================
        // Check if everything is ok.

        // Check if there is no strategy.
        if (strategyBeans.isEmpty()) {
            getLogger().error("No strategy found");
            throw new ConfigurationException("No strategy found",
                    "You must have one class with @Strategy");
        }

        // Check if there are several strategies.
        if (strategyBeans.size() > 1) {
            getLogger().error("Several strategies found");
            strategyBeans.forEach((s, o) -> getLogger().error(" - " + s));
            throw new ConfigurationException("Several strategies found",
                    "Cassandre trading bot only supports one strategy at a time (@Strategy)");
        }

        // Check if the strategy extends CassandreStrategy.
        Object o = strategyBeans.values().iterator().next();
        if (!(o instanceof CassandreStrategyInterface)) {
            throw new ConfigurationException("Your strategy doesn't extend BasicCassandreStrategy or BasicTa4jCassandreStrategy",
                    o.getClass() + " must extend BasicCassandreStrategy or BasicTa4jCassandreStrategy");
        }

        // Check that the trading account the strategy asks for really exists.
        final Optional<UserDTO> user = userService.getUser();
        if (user.isPresent()) {
            final Optional<AccountDTO> tradeAccount = ((CassandreStrategyInterface) o).getTradeAccount(new LinkedHashSet<>(user.get().getAccounts().values()));
            if (tradeAccount.isEmpty()) {
                StringJoiner accountList = new StringJoiner(", ");
                user.get().getAccounts().values().forEach(accountDTO -> accountList.add(accountDTO.getName()));
                throw new ConfigurationException("Your strategy specifies a trading account that doesn't exist",
                        "Check your getTradeAccount(Set<AccountDTO> accounts) method as it returns an empty result - Account list : " + accountList);
            }
        } else {
            throw new ConfigurationException("Impossible to retrieve your user information",
                    "Impossible to retrieve your user information. Check logs.");
        }

        // =============================================================================================================
        // Getting strategy information.
        CassandreStrategyInterface strategy = (CassandreStrategyInterface) o;

        // Displaying strategy name.
        CassandreStrategy cassandreStrategyAnnotation = o.getClass().getAnnotation(CassandreStrategy.class);
        getLogger().info("StrategyConfiguration - Running strategy '{}'", cassandreStrategyAnnotation.name());

        // Displaying requested currency pairs.
        StringJoiner currencyPairList = new StringJoiner(", ");
        strategy.getRequestedCurrencyPairs()
                .forEach(currencyPair -> currencyPairList.add(currencyPair.toString()));
        getLogger().info("StrategyConfiguration - The strategy requires the following currency pair(s) : " + currencyPairList);

        // =============================================================================================================
        // Setting up strategy.

        // Setting services.
        strategy.setTradeService(tradeService);
        strategy.setPositionService(positionService);
        restoreData(strategy);

        // Account flux.
        final ConnectableFlux<AccountDTO> connectableAccountFlux = accountFlux.getFlux().publish();
        connectableAccountFlux.subscribe(strategy::accountUpdate);
        connectableAccountFlux.connect();

        // Position flux.
        final ConnectableFlux<PositionDTO> connectablePositionFlux = positionFlux.getFlux().publish();
        connectablePositionFlux.subscribe(strategy::positionUpdate);        // For strategy.
        connectablePositionFlux.subscribe(positionService::backupPosition); // For position backup.
        connectablePositionFlux.connect();

        // Order flux.
        final ConnectableFlux<OrderDTO> connectableOrderFlux = orderFlux.getFlux().publish();
        connectableOrderFlux.subscribe(strategy::orderUpdate);
        connectableOrderFlux.connect();

        // Trade flux to strategy.
        final ConnectableFlux<TradeDTO> connectableTradeFlux = tradeFlux.getFlux().publish();
        connectableTradeFlux.subscribe(strategy::tradeUpdate);              // For strategy.
        connectableTradeFlux.subscribe(positionService::tradeUpdate);       // For position service.
        connectableTradeFlux.subscribe(tradeService::backupTrade);          // For trade backup.
        connectableTradeFlux.connect();

        // Ticker flux.
        tickerFlux.updateRequestedCurrencyPairs(strategy.getRequestedCurrencyPairs());
        final ConnectableFlux<TickerDTO> connectableTickerFlux = tickerFlux.getFlux().publish();
        connectableTickerFlux.subscribe(strategy::tickerUpdate);            // For strategy.
        connectableTickerFlux.subscribe(positionService::tickerUpdate);     // For position service.
        // if in dry mode, we also send the ticker to the dry mode.
        if (tradeService instanceof TradeServiceDryModeImplementation) {
            connectableTickerFlux.subscribe(((TradeServiceDryModeImplementation) tradeService)::tickerUpdate);
        }
        connectableTickerFlux.connect();

        // If in dry mode, we setup dependencies.
        if (userService instanceof UserServiceDryModeImplementation) {
            ((UserServiceDryModeImplementation) userService).setDependencies((GenericCassandreStrategy) strategy);
        }
    }

    /**
     * Restore data from database.
     *
     * @param strategy strategy
     */
    private void restoreData(final CassandreStrategyInterface strategy) {
        // Restoring all trades.
        final Map<String, TradeDTO> tradesByOrderId = new LinkedHashMap<>();
        final Map<String, TradeDTO> tradesById = new LinkedHashMap<>();
        getLogger().info("Restoring trades from database");
        AtomicInteger tradeCount = new AtomicInteger(0);
        tradeRepository.findByOrderByTimestampAsc()
                .forEach(trade -> {
                    TradeDTO t = TradeDTO.builder()
                            .id(trade.getId())
                            .orderId(trade.getOrderId())
                            .type(OrderTypeDTO.valueOf(trade.getType()))
                            .originalAmount(trade.getOriginalAmount())
                            .currencyPair(new CurrencyPairDTO(trade.getCurrencyPair()))
                            .price(trade.getPrice())
                            .timestamp(trade.getTimestamp())
                            .feeAmount(trade.getFeeAmount())
                            .feeCurrency(new CurrencyDTO(trade.getFeeCurrency()))
                            .create();
                    tradesByOrderId.put(t.getOrderId(), t);
                    tradesById.put(t.getId(), t);
                    strategy.restoreTrade(t);
                    tradeService.restoreTrade(t);
                    tradeFlux.restoreTrade(t);
                    tradeCount.incrementAndGet();
                    getLogger().info("Trade " + trade.getOrderId() + " restored : " + t);
                });
        getLogger().info(tradeCount.get() + " trade(s) restored");

        // Restoring data from databases.
        getLogger().info("Restoring positions from database");
        AtomicInteger positionCount = new AtomicInteger(0);
        positionRepository.findAll().forEach(position -> {
            PositionRulesDTO rules = PositionRulesDTO.builder().create();
            boolean stopGainRuleSet = position.getStopGainPercentageRule() != null;
            boolean stopLossRuleSet = position.getStopLossPercentageRule() != null;
            // Two rules set.
            if (stopGainRuleSet && stopLossRuleSet) {
                rules = PositionRulesDTO.builder()
                        .stopGainPercentage(position.getStopGainPercentageRule())
                        .stopLossPercentage(position.getStopLossPercentageRule())
                        .create();
            }
            // Stop gain set.
            if (stopGainRuleSet && !stopLossRuleSet) {
                rules = PositionRulesDTO.builder()
                        .stopGainPercentage(position.getStopGainPercentageRule())
                        .create();
            }
            // Stop loss set.
            if (!stopGainRuleSet && stopLossRuleSet) {
                rules = PositionRulesDTO.builder()
                        .stopLossPercentage(position.getStopLossPercentageRule())
                        .create();
            }
            Set<TradeDTO> positionTrades = new LinkedHashSet<>();
            position.getTrades()
                    .forEach(s -> positionTrades.add(tradesById.get(s)));
            PositionDTO p = new PositionDTO(position.getId(),
                    PositionStatusDTO.valueOf(position.getStatus()),
                    new CurrencyPairDTO(position.getCurrencyPair()),
                    position.getAmount(),
                    rules,
                    position.getOpenOrderId(),
                    position.getCloseOrderId(),
                    positionTrades,
                    position.getLowestPrice(),
                    position.getHighestPrice());
            positionService.restorePosition(p);
            strategy.restorePosition(p);
            positionFlux.restorePosition(p);
            positionCount.incrementAndGet();
            getLogger().info("Position " + position.getId() + " restored : " + p);
        });
        getLogger().info(positionCount.get() + " position(s) restored");
    }

}
