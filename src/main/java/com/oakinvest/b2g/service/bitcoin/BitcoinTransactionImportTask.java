package com.oakinvest.b2g.service.bitcoin;

import com.oakinvest.b2g.domain.bitcoin.BitcoinTransaction;
import com.oakinvest.b2g.domain.bitcoin.BitcoinTransactionInput;
import com.oakinvest.b2g.domain.bitcoin.BitcoinTransactionOutput;
import com.oakinvest.b2g.dto.external.bitcoind.getrawtransaction.GetRawTransactionResponse;
import com.oakinvest.b2g.repository.bitcoin.BitcoinAddressRepository;
import com.oakinvest.b2g.repository.bitcoin.BitcoinTransactionRepository;
import com.oakinvest.b2g.service.StatusService;
import com.oakinvest.b2g.util.bitcoin.BitcoindToDomainMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.Future;

/**
 * Bitcoin transaction integration task.
 * Created by straumat on 17/02/17.
 */
@Component
public class BitcoinTransactionImportTask {

	/**
	 * Logger.
	 */
	private Logger log = LoggerFactory.getLogger(BitcoinTransactionImportTask.class);

	/**
	 * Bitcoind service.
	 */
	@Autowired
	private BitcoindService bds;

	/**
	 * Status service.
	 */
	@Autowired
	private StatusService status;

	/**
	 * Bitcoin transaction repository.
	 */
	@Autowired
	private BitcoinTransactionRepository btr;

	/**
	 * Bitcoin address repository.
	 */
	@Autowired
	private BitcoinAddressRepository bar;

	/**
	 * Mapper.
	 */
	@Autowired
	private BitcoindToDomainMapper mapper;

	/**
	 * Set environment.
	 *
	 * @param nLog    log
	 * @param nBds    bds
	 * @param nStatus status
	 * @param nBtr    btr
	 * @param nBar    bar
	 * @param nMapper mapper
	 */
	public final void setEnvironment(final Logger nLog, final BitcoindService nBds, final StatusService nStatus, final BitcoinTransactionRepository nBtr, final BitcoinAddressRepository nBar, final BitcoindToDomainMapper nMapper) {
		log = nLog;
		bds = nBds;
		status = nStatus;
		btr = nBtr;
		bar = nBar;
		mapper = nMapper;
	}

	/**
	 * Create a transaction in the database.
	 *
	 * @param transactionHash transaction hash.
	 * @return transaction.
	 */
	@Async
	@Transactional
	@SuppressWarnings("checkstyle:designforextension")
	public Future<BitcoinTransaction> createTransaction(final String transactionHash) {
		BitcoinTransaction transaction = btr.findByTxId(transactionHash);
		if (transaction != null) {
			// If the transaction already exists in the database, we return it.
			log.info("Transaction " + transactionHash + " is already in the database");
			return new AsyncResult<>(transaction);
		} else {
			// If the transaction is not in the database, we build it.
			log.info("Transaction " + transactionHash + " is not in the database. Creating it");
			GetRawTransactionResponse transactionResponse = bds.getRawTransaction(transactionHash);
			if (transactionResponse.getError() == null) {
				// Success.
				try {
					// Saving the transaction in the database.
					BitcoinTransaction bt = mapper.rawTransactionResultToBitcoinTransaction(transactionResponse.getResult());

					// For each Vin.
					Iterator<BitcoinTransactionInput> vins = bt.getInputs().iterator();
					while (vins.hasNext()) {
						BitcoinTransactionInput vin = vins.next();
						vin.setTransaction(bt);
						if (vin.getTxId() != null) {
							// Not coinbase. We retrieve the original transaction.
							Optional<BitcoinTransactionOutput> originTransactionOutput = btr.findByTxId(vin.getTxId()).getOutputByIndex(vin.getvOut());
							if (originTransactionOutput.isPresent()) {
								vin.setTransactionOutput(originTransactionOutput.get());
								// We set the addresses "from" if it's not a coinbase transaction.
								originTransactionOutput.get().getAddresses().forEach(a -> (bar.findByAddress(a)).getWithdrawals().add(vin));
								log.info(">> Done treating vin : " + vin);
							} else {
								log.error("Impossible to find the original output transaction " + vin.getTxId() + " / " + vin.getvOut());
								return new AsyncResult<>(null);
							}
						}

					}

					// For each Vout.
					Iterator<BitcoinTransactionOutput> vouts = bt.getOutputs().iterator();
					while (vouts.hasNext()) {
						BitcoinTransactionOutput vout = vouts.next();
						vout.setTransaction(bt);
						vout.getAddresses().forEach(a -> (bar.findByAddress(a)).getDeposits().add(vout));
						log.info(">> Done treating vout : " + vout);
					}

					// Saving the transaction.
					btr.save(bt);
					status.addLog("> Transaction " + transactionHash + " created with id " + bt.getId());
					return new AsyncResult<>(bt);
				} catch (Exception e) {
					status.addError("Error treating transaction " + transactionHash + " : " + e.getMessage());
					e.printStackTrace();
					return new AsyncResult<>(null);
				}
			} else {
				// Error.
				status.addError("Error in calling getrawtransaction " + transactionResponse.getError());
				return new AsyncResult<>(null);
			}
		}
	}

}
