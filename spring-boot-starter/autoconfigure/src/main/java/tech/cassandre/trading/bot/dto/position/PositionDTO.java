package tech.cassandre.trading.bot.dto.position;

import tech.cassandre.trading.bot.dto.market.TickerDTO;
import tech.cassandre.trading.bot.dto.trade.TradeDTO;
import tech.cassandre.trading.bot.dto.util.CurrencyAmountDTO;
import tech.cassandre.trading.bot.dto.util.CurrencyPairDTO;
import tech.cassandre.trading.bot.dto.util.GainDTO;
import tech.cassandre.trading.bot.util.exception.PositionException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static tech.cassandre.trading.bot.dto.position.PositionStatusDTO.CLOSED;
import static tech.cassandre.trading.bot.dto.position.PositionStatusDTO.CLOSING;
import static tech.cassandre.trading.bot.dto.position.PositionStatusDTO.OPENED;
import static tech.cassandre.trading.bot.dto.position.PositionStatusDTO.OPENING;
import static tech.cassandre.trading.bot.dto.trade.OrderTypeDTO.ASK;
import static tech.cassandre.trading.bot.dto.trade.OrderTypeDTO.BID;

/**
 * DTO representing a position.
 * A position is the amount of a security, commodity or currency which is owned by an individual, dealer, institution, or other fiscal entity.
 */
public class PositionDTO {

    /** An identifier that uniquely identifies the position. */
    private final long id;

    /** Position version (used for database backup). */
    private final AtomicLong version = new AtomicLong(0L);

    /** Position status. */
    private PositionStatusDTO status = OPENING;

    /** Currency pair. */
    private final CurrencyPairDTO currencyPair;

    /** Amount ordered. */
    private final BigDecimal amount;

    /** Position rules. */
    private final PositionRulesDTO rules;

    /** The order id that opened the position. */
    private final String openOrderId;

    /** The order id that closed the position. */
    private String closeOrderId;

    /** The trades that closed the position. */
    private final Map<String, TradeDTO> trades = new LinkedHashMap<>();

    /** Last calculated gain from the last ticker received. */
    private GainDTO lastCalculatedGain;

    /** Lowest price for this position. */
    private BigDecimal lowestPrice;

    /** Highest price for this position. */
    private BigDecimal highestPrice;

    /** Percentage. */
    private static final int ONE_HUNDRED = 100;

    /** Big integer scale. */
    private static final int BIGINTEGER_SCALE = 4;

    /**
     * Constructor.
     *
     * @param newId           position id
     * @param newCurrencyPair currency pair
     * @param newAmount       amount
     * @param newOpenOrderId  open order id
     * @param newRules        position rules
     */
    public PositionDTO(final long newId,
                       final CurrencyPairDTO newCurrencyPair,
                       final BigDecimal newAmount,
                       final String newOpenOrderId,
                       final PositionRulesDTO newRules) {
        this.id = newId;
        this.currencyPair = newCurrencyPair;
        this.amount = newAmount;
        this.openOrderId = newOpenOrderId;
        this.rules = newRules;
        version.incrementAndGet();
    }

    /**
     * Constructor (only used when restoring from database).
     *
     * @param newId           position id
     * @param newStatus       status
     * @param newCurrencyPair currency pair
     * @param newAmount       amount
     * @param newRules        position rules
     * @param newOpenOrderId  open order id
     * @param newCloseOrderId close order id
     * @param newTrades       close trades
     * @param newLowestPrice  lowest price
     * @param newHighestPrice highest price
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public PositionDTO(final long newId,
                       final PositionStatusDTO newStatus,
                       final CurrencyPairDTO newCurrencyPair,
                       final BigDecimal newAmount,
                       final PositionRulesDTO newRules,
                       final String newOpenOrderId,
                       final String newCloseOrderId,
                       final Set<TradeDTO> newTrades,
                       final BigDecimal newLowestPrice,
                       final BigDecimal newHighestPrice) {
        this.id = newId;
        this.status = newStatus;
        this.rules = newRules;
        this.openOrderId = newOpenOrderId;
        this.currencyPair = newCurrencyPair;
        this.amount = newAmount;
        this.closeOrderId = newCloseOrderId;
        if (newTrades != null) {
            newTrades.forEach(t -> trades.put(t.getId(), t));
        }
        this.lowestPrice = newLowestPrice;
        this.highestPrice = newHighestPrice;
    }

    /**
     * Setter for closeOrderId.
     *
     * @param newCloseOrderId the closeOrderId to set
     */
    public final void setCloseOrderId(final String newCloseOrderId) {
        // This method should only be called when in status OPENED.
        if (status != OPENED) {
            throw new PositionException("Impossible to set close order id for position " + id);
        }
        status = CLOSING;
        closeOrderId = newCloseOrderId;
        version.incrementAndGet();
    }

    /**
     * Method called by on every trade update.
     *
     * @param trade trade
     */
    public void tradeUpdate(final TradeDTO trade) {
        // If status is OPENING and the trades for the open order arrives for the whole amount ==> status = OPENED.
        if (trade.getOrderId().equals(openOrderId) && status == OPENING) {
            trades.put(trade.getId(), trade);

            // We calculate the sum of amount in the all the trades.
            // If it reaches the original amount we order, we consider the trade opened.
            if (amount.compareTo(getTotalAmountFromOpenTrades()) == 0) {
                status = OPENED;
            }
            version.incrementAndGet();
        }
        // If status is CLOSING and the trades for the close order arrives for the whole amount ==> status = CLOSED.
        if (trade.getOrderId().equals(closeOrderId) && status == CLOSING) {
            trades.put(trade.getId(), trade);

            // We calculate the sum of amount in the all the trades.
            // If it reaches the original amount we order, we consider the trade opened.
            if (amount.compareTo(getTotalAmountFromCloseTrades()) == 0) {
                status = CLOSED;
            }
            version.incrementAndGet();
        }
    }

    /**
     * Returns true if the position should be closed.
     *
     * @param ticker ticker
     * @return true if the rules says the position should be closed.
     */
    public boolean shouldBeClosed(final TickerDTO ticker) {
        // The status must be OPENED to be closed.
        // The currency pair of the ticker must be the same than the currency pair of the open trade.
        if (closeOrderId != null || !ticker.getCurrencyPair().equals(currencyPair)) {
            return false;
        } else {
            final Optional<GainDTO> gain = calculateGainFromPrice(ticker.getLast());
            if (gain.isPresent()) {
                // We save the last calculated gain.
                this.lastCalculatedGain = gain.get();

                if (rules.isStopGainPercentageSet() && gain.get().getPercentage() >= rules.getStopGainPercentage()
                        || rules.isStopLossPercentageSet() && gain.get().getPercentage() <= -rules.getStopLossPercentage()) {
                    version.incrementAndGet();
                    // If the rules tells we should sell.
                    return true;
                } else {
                    // We check if this gain is at a new highest.
                    if (highestPrice == null) {
                        highestPrice = ticker.getLast();
                        version.incrementAndGet();
                    } else {
                        final Optional<GainDTO> highestGain = calculateGainFromPrice(highestPrice);
                        if (highestGain.isPresent() && highestGain.get().getPercentage() <= gain.get().getPercentage()) {
                            highestPrice = ticker.getLast();
                            version.incrementAndGet();
                        }
                    }
                    // We check if this gain is at a new lowest.
                    if (lowestPrice == null) {
                        lowestPrice = ticker.getLast();
                        version.incrementAndGet();
                    } else {
                        final Optional<GainDTO> lowestGain = calculateGainFromPrice(lowestPrice);
                        if (lowestGain.isPresent() && lowestGain.get().getPercentage() >= gain.get().getPercentage()) {
                            lowestPrice = ticker.getLast();
                            version.incrementAndGet();
                        }
                    }
                    return false;
                }
            } else {
                return false;
            }
        }
    }

    /**
     * Calculate the gain from a price.
     *
     * @param price price
     * @return gain
     */
    private Optional<GainDTO> calculateGainFromPrice(final BigDecimal price) {
        if ((status == OPENED || status == CLOSED) && price != null) {
            // We take the price from the first trade received.
            final TradeDTO openTrade = getOpenTrades().iterator().next();

            // How gain calculation works ?
            //  - Bought 10 ETH with a price of 5 -> Amount of 50.
            //  - Sold 10 ETH with a price of 6 -> Amount of 60.
            //  Gain = (6-5)/5 = 20%.
            float gainPercentage = (price.subtract(openTrade.getPrice()))
                    .divide(openTrade.getPrice(), BIGINTEGER_SCALE, RoundingMode.FLOOR)
                    .floatValue() * ONE_HUNDRED;
            BigDecimal gainAmount = ((openTrade.getOriginalAmount().multiply(price))
                    .subtract((openTrade.getOriginalAmount()).multiply(openTrade.getPrice())));

            GainDTO gain = new GainDTO(gainPercentage,
                    new CurrencyAmountDTO(gainAmount, currencyPair.getQuoteCurrency()),
                    new CurrencyAmountDTO(BigDecimal.ZERO, currencyPair.getQuoteCurrency()));
            return Optional.of(gain);
        } else {
            return Optional.empty();
        }
    }

    /**
     * Returns the gain of the position.
     * Of course the position should be closed to have a gain.
     *
     * @return gain
     */
    public GainDTO getGain() {
        if (status == CLOSED) {
            // Gain calculation for currency pair : ETH-BTC
            // The first listed currency of a currency pair is called the base currency.
            // The second currency is called the quote currency.

            // Price is 0.035547 means 1 Ether equals 0.035547 Bitcoin
            // If you buy a currency pair, you buy the base currency and implicitly sell the quoted currency.

            // - Bought 10 ETH with a price of 4 BTC -> costs 40 BTC.
            // - Bought 20 ETH with a price of 3 BTC -> costs 60 BTC.
            // - Sold 10 ETH with a price of 3 BTC -> earns 30 BTC.
            // - Sold 05 ETH with a price of 6 BTC -> earns 30 BTC
            // - Sold 15 ETH with a price of 6 BTC -> earns 90 BTC
            // ---
            // To start the position, I spent 100 BTC.
            // When I closed the position, I received 150 BTC
            // Gain  -> ((150 - 100) / 100) * 100 = 50 %
            BigDecimal bought = getOpenTrades()
                    .stream()
                    .map(t -> t.getOriginalAmount().multiply(t.getPrice()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal sold = getCloseTrades()
                    .stream()
                    .map(t -> t.getOriginalAmount().multiply(t.getPrice()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Calculate gain.
            BigDecimal gainAmount = sold.subtract(bought);
            double gainPercentage = ((sold.subtract(bought)).divide(bought)).floatValue() * ONE_HUNDRED;

            // Return position gain.
            return new GainDTO(gainPercentage,
                    new CurrencyAmountDTO(gainAmount, currencyPair.getQuoteCurrency()),
                    new CurrencyAmountDTO(getTotalFees(), currencyPair.getQuoteCurrency()));
        } else {
            // No gain for the moment !
            return new GainDTO();
        }
    }

    /**
     * Get total fees from all trades.
     *
     * @return fees
     */
    private BigDecimal getTotalFees() {
        return trades.values()
                .stream()
                .map(t -> t.getFee().getValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Returns the total amount from open trades.
     *
     * @return total
     */
    private BigDecimal getTotalAmountFromOpenTrades() {
        return trades.values()
                .stream()
                .filter(t -> BID.equals(t.getType()))
                .map(TradeDTO::getOriginalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Returns the total amount from close trades.
     *
     * @return total
     */
    private BigDecimal getTotalAmountFromCloseTrades() {
        return trades.values()
                .stream()
                .filter(t -> ASK.equals(t.getType()))
                .map(TradeDTO::getOriginalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Getter for id.
     *
     * @return id
     */
    public final long getId() {
        return id;
    }

    /**
     * Getter for status.
     *
     * @return status
     */
    public final PositionStatusDTO getStatus() {
        return status;
    }

    /**
     * Get currency pair.
     *
     * @return currency pair
     */
    public final CurrencyPairDTO getCurrencyPair() {
        return currencyPair;
    }

    /**
     * Getter amount.
     *
     * @return amount
     */
    public final BigDecimal getAmount() {
        return amount;
    }

    /**
     * Getter rules.
     *
     * @return rules
     */
    public final PositionRulesDTO getRules() {
        return rules;
    }

    /**
     * Getter trades.
     *
     * @return trades
     */
    public final Set<TradeDTO> getTrades() {
        return new HashSet<>(trades.values());
    }

    /**
     * Returns trade from its id.
     *
     * @param tradeId trade id
     * @return trade
     */
    public final Optional<TradeDTO> getTrade(final String tradeId) {
        if (tradeId == null) {
            return Optional.empty();
        } else {
            return trades.values()
                    .stream()
                    .filter(t -> tradeId.equals(t.getId()))
                    .findFirst();
        }
    }

    /**
     * Getter openTrades.
     *
     * @return openTrades
     */
    public final Set<TradeDTO> getOpenTrades() {
        return trades.values()
                .stream()
                .filter(t -> BID.equals(t.getType()))
                .collect(Collectors.toSet());
    }

    /**
     * Getter closeTrades.
     *
     * @return closeTrades
     */
    public final Set<TradeDTO> getCloseTrades() {
        return trades.values()
                .stream()
                .filter(t -> ASK.equals(t.getType()))
                .collect(Collectors.toSet());
    }

    /**
     * Getter openOrderId.
     *
     * @return openOrderId
     */
    public final String getOpenOrderId() {
        return openOrderId;
    }

    /**
     * Getter last calculated gain from the last ticker received.
     *
     * @return lastCalculatedGain
     */
    public final Optional<GainDTO> getLastCalculatedGain() {
        return Optional.ofNullable(lastCalculatedGain);
    }

    /**
     * Getter closeOrderId.
     *
     * @return closeOrderId
     */
    public final String getCloseOrderId() {
        return closeOrderId;
    }

    /**
     * Getter lowestPrice.
     *
     * @return lowestPrice
     */
    public final BigDecimal getLowestPrice() {
        return lowestPrice;
    }

    /**
     * Getter highestPrice.
     *
     * @return highestPrice
     */
    public final BigDecimal getHighestPrice() {
        return highestPrice;
    }

    /**
     * Getter lowestCalculatedGain.
     *
     * @return lowestCalculatedGain
     */
    public final Optional<GainDTO> getLowestCalculatedGain() {
        return calculateGainFromPrice(lowestPrice);
    }

    /**
     * Getter highestCalculatedGain.
     *
     * @return highestCalculatedGain
     */
    public final Optional<GainDTO> getHighestCalculatedGain() {
        return calculateGainFromPrice(highestPrice);
    }

    /**
     * Getter version.
     *
     * @return version
     */
    public final Long getVersion() {
        return version.longValue();
    }

    /**
     * Returns formatted value.
     *
     * @param value value
     * @return formatted value
     */
    private String getFormattedValue(final BigDecimal value) {
        return new DecimalFormat("#0.##").format(value);
    }

    /**
     * Returns formatted value.
     *
     * @param value value
     * @return formatted value
     */
    private String getFormattedValue(final double value) {
        return new DecimalFormat("#0.##").format(value);
    }

    @Override
    public final boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PositionDTO that = (PositionDTO) o;
        return id == that.id && status == that.status;
    }

    @Override
    public final int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public final String toString() {
        try {
            String value = "Position n°" + id + " (";
            // Rules.
            if (!rules.isStopGainPercentageSet() && !rules.isStopLossPercentageSet()) {
                value += "no rules";
            }
            if (rules.isStopGainPercentageSet() && !rules.isStopLossPercentageSet()) {
                value += rules.getStopGainPercentage() + " % gain rule";
            }
            if (rules.isStopLossPercentageSet() && !rules.isStopGainPercentageSet()) {
                value += rules.getStopLossPercentage() + " % loss rule";
            }
            if (rules.isStopGainPercentageSet() && rules.isStopLossPercentageSet()) {
                value += rules.getStopGainPercentage() + " % gain rule / ";
                value += rules.getStopLossPercentage() + " % loss rule";
            }
            value += ")";
            switch (status) {
                case OPENING:
                    value += " - Opening - Waiting for the trade of order " + getOpenOrderId();
                    break;
                case OPENED:
                    value += " on " + getCurrencyPair() + " - Opened";
                    final Optional<GainDTO> lastGain = getLastCalculatedGain();
                    if (lastGain.isPresent()) {
                        value += " - Last gain calculated " + getFormattedValue(getLastCalculatedGain().get().getPercentage()) + " %";
                    }
                    break;
                case CLOSING:
                    value += " on " + getCurrencyPair() + " - Closing - Waiting for the trade of order " + getCloseOrderId();
                    break;
                case CLOSED:
                    final GainDTO gain = getGain();
                    value += " on " + getCurrencyPair() + " - Closed - Gain : " + getFormattedValue(gain.getPercentage()) + " %";
                    break;
                default:
                    value = "Incorrect state for position " + getId();
                    break;
            }
            return value;
        } catch (Exception e) {
            // TODO Check why some errors appears if we only catch nullException.
            return "Position " + getId();
        }
    }

}
