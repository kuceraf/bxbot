package com.gazbert.bxbot.strategies.pokus;

import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.*;
import org.easymock.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.easymock.EasyMock.*;

@RunWith(EasyMockRunner.class)
public class PokusStrategyTets {
    @Mock // create a Mock Object for the interface we would like to simulate
    private TradingApi tradingApiMock;
    @Mock
    private Market marketMock;
    @Mock
    private StrategyConfig configMock;

    @TestSubject
    private PokusStrategy pokusStrategyTested = new PokusStrategy();

    // Config
    final String MARKET_NAME = "BTC_USD";
    final String MARKET_ID = "BTC-EUR";
    final String MARKET_BASE_CURRENCY = "BTC";
    final String MARKET_COUNTER_CURRENCY = "EUR";

    // Gdax fake data
    final BigDecimal GDAX_LAST_MARKET_PRICE = BigDecimal.valueOf(13310.00);
    final BigDecimal GDAX_BUY_PRICE = BigDecimal.valueOf(13350.00);
    final BigDecimal GDAX_SELL_PRICE = BigDecimal.valueOf(13300.00);
    final String GDAX_ORDER_ID = "gdax-order-id";

    @Before
    public void setUpBeforeEachTest() throws Exception {
        // Prepare stubs
        expect(this.marketMock.getName())
                .andStubReturn(MARKET_NAME);
        expect(marketMock.getId())
                .andStubReturn(MARKET_ID);
        expect(marketMock.getBaseCurrency())
                .andStubReturn(MARKET_BASE_CURRENCY);
        expect(marketMock.getCounterCurrency())
                .andStubReturn(MARKET_COUNTER_CURRENCY);
        replay(marketMock); // switch the Mock Object to replay state
    }

    @Test
    public void execute_sendsInitialBuyOrderWhenItIsFirstCalled() throws Exception {
        // Record state (mock object records methods calls)
        expect(tradingApiMock.getMarketOrders(MARKET_ID))
                .andReturn(createMarketOrderBook());
        expect(tradingApiMock.getLatestMarketPrice(MARKET_ID))
                .andReturn(GDAX_LAST_MARKET_PRICE);
        expect(tradingApiMock.createOrder(MARKET_ID,
                OrderType.BUY,
                PokusStrategy.COUNTER_CURRENCY_BUY_ORDER_AMOUNT.divide(GDAX_LAST_MARKET_PRICE, 8 , RoundingMode.HALF_DOWN),
                GDAX_BUY_PRICE))
                .andReturn(GDAX_ORDER_ID);
        expect(tradingApiMock.getYourOpenOrders(MARKET_ID))
            .andReturn(Collections.singletonList(createOpenOrder()));

        replay(tradingApiMock);

        pokusStrategyTested.execute();
        verify();
    }


    private OpenOrder createOpenOrder() {
        return new OpenOrder(GDAX_ORDER_ID, null, null, null, null, null, null, null);
    }
    private MarketOrderBook createMarketOrderBook() {
        List<MarketOrder> buyOrders= Arrays.asList(new MarketOrder(OrderType.BUY, BigDecimal.valueOf(13350.00), BigDecimal.valueOf(6.98), null));
        List<MarketOrder> sellOrders= Arrays.asList(new MarketOrder(OrderType.SELL, BigDecimal.valueOf(13300.00), BigDecimal.valueOf(4.98), null));
        return new MarketOrderBook(MARKET_ID, sellOrders, buyOrders);
    }
}
