package tech.cassandre.trading.bot.test.batch.mocks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tech.cassandre.trading.bot.batch.AccountFlux;
import tech.cassandre.trading.bot.batch.OrderFlux;
import tech.cassandre.trading.bot.batch.TickerFlux;
import tech.cassandre.trading.bot.dto.user.AccountDTO;
import tech.cassandre.trading.bot.dto.user.BalanceDTO;
import tech.cassandre.trading.bot.dto.user.UserDTO;
import tech.cassandre.trading.bot.dto.util.CurrencyDTO;
import tech.cassandre.trading.bot.dto.util.CurrencyPairDTO;
import tech.cassandre.trading.bot.service.MarketService;
import tech.cassandre.trading.bot.service.TradeService;
import tech.cassandre.trading.bot.service.UserService;
import tech.cassandre.trading.bot.test.util.junit.BaseTest;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static tech.cassandre.trading.bot.dto.util.CurrencyDTO.BTC;
import static tech.cassandre.trading.bot.dto.util.CurrencyDTO.ETH;
import static tech.cassandre.trading.bot.dto.util.CurrencyDTO.USDT;

@TestConfiguration
public class TickerFluxTestMock extends BaseTest {

    @Bean
    @Primary
    public TickerFlux tickerFlux() {
        return new TickerFlux(marketService());
    }

    @Bean
    @Primary
    public AccountFlux accountFlux() {
        return new AccountFlux(userService());
    }

    @Bean
    @Primary
    public OrderFlux orderFlux() {
        return new OrderFlux(tradeService());
    }

    @SuppressWarnings("unchecked")
    @Bean
    @Primary
    public UserService userService() {
        Map<CurrencyDTO, BalanceDTO> balances = new LinkedHashMap<>();
        final Map<String, AccountDTO> accounts = new LinkedHashMap<>();
        UserService userService = mock(UserService.class);
        // Returns three updates.

        // Account 01.
        BalanceDTO account01Balance1 = BalanceDTO.builder().available(new BigDecimal("1")).create();
        balances.put(BTC, account01Balance1);
        AccountDTO account01 = AccountDTO.builder().id("01").name("trade").balances(balances).create();
        accounts.put("01", account01);
        UserDTO user01 = UserDTO.builder().setAccounts(accounts).create();
        balances.clear();
        accounts.clear();

        // Account 02.
        BalanceDTO account02Balance1 = BalanceDTO.builder().available(new BigDecimal("1")).create();
        balances.put(BTC, account02Balance1);
        AccountDTO account02 = AccountDTO.builder().id("02").name("trade").balances(balances).create();
        accounts.put("02", account02);
        UserDTO user02 = UserDTO.builder().setAccounts(accounts).create();
        balances.clear();
        accounts.clear();

        // Account 03.
        balances.put(BTC, BalanceDTO.builder().available(new BigDecimal("2")).create());
        balances.put(ETH, BalanceDTO.builder().available(new BigDecimal("10")).create());
        balances.put(USDT, BalanceDTO.builder().available(new BigDecimal("2000")).create());
        AccountDTO account03 = AccountDTO.builder().id("03").name("trade").balances(balances).create();
        accounts.put("03", account03);
        UserDTO user03 = UserDTO.builder().setAccounts(accounts).create();
        balances.clear();
        accounts.clear();

        // Mock replies.
        given(userService.getUser()).willReturn(Optional.of(user01), Optional.of(user02), Optional.of(user03));
        return userService;
    }

    @SuppressWarnings("unchecked")
    @Bean
    @Primary
    public MarketService marketService() {
        // Creates the mock.
        MarketService marketService = mock(MarketService.class);

        // Replies for ETH / BTC.
        final CurrencyPairDTO cp1 = new CurrencyPairDTO(ETH, BTC);
        final Date time = Calendar.getInstance().getTime();
        given(marketService
                .getTicker(cp1))
                .willReturn(BaseTest.getFakeTicker(cp1, new BigDecimal("1")),
                        BaseTest.getFakeTicker(cp1, new BigDecimal("2")),
                        BaseTest.getFakeTicker(cp1, new BigDecimal("3")),
                        Optional.empty(),
                        BaseTest.getFakeTicker(time, cp1, new BigDecimal("4")),
                        BaseTest.getFakeTicker(time, cp1, new BigDecimal("4")),
                        BaseTest.getFakeTicker(cp1, new BigDecimal("5")),
                        BaseTest.getFakeTicker(cp1, new BigDecimal("6")),
                        Optional.empty()
                );

        // Replies for ETH / USDT.
        final CurrencyPairDTO cp2 = new CurrencyPairDTO(ETH, USDT);
        given(marketService
                .getTicker(cp2))
                .willReturn(BaseTest.getFakeTicker(cp2, new BigDecimal("10")),
                        BaseTest.getFakeTicker(cp2, new BigDecimal("20")),
                        BaseTest.getFakeTicker(cp2, new BigDecimal("30")),
                        BaseTest.getFakeTicker(cp2, new BigDecimal("40")),
                        BaseTest.getFakeTicker(cp2, new BigDecimal("50")),
                        Optional.empty(),
                        BaseTest.getFakeTicker(cp2, new BigDecimal("60")),
                        Optional.empty(),
                        BaseTest.getFakeTicker(cp2, new BigDecimal("70"))
                );
        return marketService;
    }

    @Bean
    @Primary
    public TradeService tradeService() {
        TradeService service = mock(TradeService.class);
        given(service.getOpenOrders()).willReturn(new LinkedHashSet<>());
        return service;
    }

}
