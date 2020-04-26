package de.fs92.defi;

import de.fs92.defi.compounddai.CompoundDai;
import de.fs92.defi.contractneedsprovider.*;
import de.fs92.defi.dai.Dai;
import de.fs92.defi.gasprovider.GasProvider;
import de.fs92.defi.medianizer.Medianizer;
import de.fs92.defi.oasisdex.OasisDex;
import de.fs92.defi.uniswap.Uniswap;
import de.fs92.defi.util.Balances;
import de.fs92.defi.util.Ethereum;
import de.fs92.defi.util.JavaProperties;
import de.fs92.defi.weth.Weth;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import java.lang.invoke.MethodHandles;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Main {
  private static final org.slf4j.Logger logger =
      LoggerFactory.getLogger(MethodHandles.lookup().lookupClass().getSimpleName());
  private static final boolean IS_DEVELOPMENT_ENVIRONMENT = true;
  private static final BigInteger minimumGasPrice = BigInteger.valueOf(1_000000000);
  private static final BigInteger maximumGasPrice = BigInteger.valueOf(200_000000000L);

  public static void main(String[] args) {
    JavaProperties javaProperties = new JavaProperties(IS_DEVELOPMENT_ENVIRONMENT);

    String ethereumAddress = javaProperties.getValue("myEthereumAddress");
    String password = javaProperties.getValue("password");
    String infuraProjectId = javaProperties.getValue("infuraProjectId");
    boolean playSoundOnTransaction =
        Boolean.parseBoolean(javaProperties.getValue("playSoundOnTransaction"));
    boolean transactionsRequireConfirmation =
        Boolean.parseBoolean(javaProperties.getValue("transactionsRequireConfirmation"));

    CircuitBreaker circuitBreaker = new CircuitBreaker();
    Web3j web3j = new Web3jProvider(infuraProjectId).web3j;
    Credentials credentials =
        new Wallet(password, ethereumAddress, IS_DEVELOPMENT_ENVIRONMENT).getCredentials();
    GasProvider gasProvider = new GasProvider(web3j, minimumGasPrice, maximumGasPrice);
    Permissions permissions =
        new Permissions(transactionsRequireConfirmation, playSoundOnTransaction);
    ContractNeedsProvider contractNeedsProvider =
        new ContractNeedsProvider(web3j, credentials, gasProvider, permissions, circuitBreaker);

    Medianizer.setContract(contractNeedsProvider);
    Dai dai = new Dai(contractNeedsProvider);
    Weth weth = new Weth(contractNeedsProvider);
    CompoundDai compoundDai = new CompoundDai(contractNeedsProvider);
    Ethereum ethereum = new Ethereum(contractNeedsProvider);

    Balances balances = new Balances(dai, weth, compoundDai, ethereum);

    OasisDex oasisDex = new OasisDex(contractNeedsProvider, compoundDai, weth);
    Uniswap uniswap = new Uniswap(contractNeedsProvider, javaProperties, compoundDai, weth);

    dai.checkApproval(uniswap);
    dai.checkApproval(oasisDex);
    dai.checkApproval(compoundDai);
    weth.checkApproval(oasisDex);

    while (CircuitBreaker.getContinueRunning()) {
      balances.updateBalance(60);
      if (circuitBreaker.isAllowingOperations(3)) {
        balances.checkEnoughEthereumForGas();
        oasisDex.checkIfSellDaiIsProfitableThenDoIt(balances);
        oasisDex.checkIfBuyDaiIsProfitableThenDoIt(balances);
        uniswap.checkIfSellDaiIsProfitableThenDoIt(balances);
        uniswap.checkIfBuyDaiIsProfitableThenDoIt(balances);
        compoundDai.lendDai(balances);
      }

      List<Long> failedTransactions = circuitBreaker.getFailedTransactions();
      if (!failedTransactions.isEmpty()) {
        circuitBreaker.update();
        gasProvider.updateFailedTransactions(failedTransactions);
      }

      try {
        TimeUnit.MILLISECONDS.sleep(4500);
      } catch (InterruptedException e) {
        logger.error("Exception", e);
        Thread.currentThread().interrupt();
      }
    }
  }
}