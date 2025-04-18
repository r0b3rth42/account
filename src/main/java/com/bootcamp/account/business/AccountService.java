package com.bootcamp.account.business;

import com.bootcamp.account.client.CreditoClient;
import com.bootcamp.account.enums.AccountType;
import com.bootcamp.account.enums.CustomerType;
import com.bootcamp.account.mapper.AccountMapper;
import com.bootcamp.account.model.dto.AccountDto;
import com.bootcamp.account.model.entity.Account;
import com.bootcamp.account.model.dto.CustomerDto;
import com.bootcamp.account.reglas.AccountValidationChain;
import com.bootcamp.account.reglas.ValidationChainFactory;
import com.bootcamp.account.repository.AccountRepositoryDepre;
import lombok.RequiredArgsConstructor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;


@RequiredArgsConstructor
public class AccountService {

    private final AccountRepositoryDepre accountRepository;
    private final WebClient.Builder webClientBuilder;
    private final CreditoClient creditoClient;

    public Flux<Account> findAll() {
        return accountRepository.findAll();
    }

    public Mono<Account> findById(String id) {
        return accountRepository.findById(id)
                .doOnNext(e -> {
                    System.out.println(e);;
                });
    }

    public Mono<Account> findByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .doOnNext(e -> {
                    System.out.println(e);;
                });
    }

    public Flux<Account> findByCustomerId(String customerId) {
        return accountRepository.findByCustomerId(customerId);
    }

    public Mono<Account> findByIdAndCustomerId(String id, String customerId) {
        return accountRepository.findByIdAndCustomerId(id, customerId);
    }

    public Mono<Account> create(AccountDto dto) {
        var account = AccountMapper.dtoToEntity(dto);
        validaciones(dto);
        return Mono.just(account)
            .flatMap(this::fetchCustomerData)
                .flatMap(customer -> {
                    AccountValidationChain chain = ValidationChainFactory.forCustomerType(customer.getType(), creditoClient);
                    return chain.execute(account, customer, accountRepository)
                            .then(saveNewAccount(account));
                });
    }

    private Mono<CustomerDto> fetchCustomerData(Account account) {
        return webClientBuilder.build()
            .get()
            .uri("http://localhost:8085/api/v1/customer/{id}", account.getCustomerId())
            .retrieve()
            .bodyToMono(CustomerDto.class)
            .map(customer -> customer);
    }


    private Mono<Void> validateCustomerType(String customerId, AccountType accountType) {
        return webClientBuilder.build()
            .get()
            .uri("http://localhost:8085/api/v1/customer/{id}", customerId)
            .retrieve()
            .bodyToMono(CustomerDto.class)
            .flatMap(customer -> {
                if (customer.getType() == CustomerType.BUSINESS &&
                        (accountType == AccountType.AHORRO || accountType == AccountType.PLAZO_FIJO)) {
                    return Mono.error(new IllegalArgumentException(
                            "Business customers cannot have savings or fixed-term accounts"));
                }
                return Mono.empty();
            });
    }

    private Mono<Void> validateAccountLimits(String customerId, AccountType accountType) {
        if (accountType == AccountType.AHORRO || accountType == AccountType.CUENTA_CORRIENTE) {
            return accountRepository.findByCustomerIdAndType(customerId, accountType)
                    .count()
                    .flatMap(count -> {
                        if (count > 0) {
                            return Mono.error(new IllegalArgumentException(
                                    "Customer already has an account of this type"));
                        }
                        return Mono.empty();
                    });
        }
        return Mono.empty();
    }

    private Mono<Account> saveNewAccount(Account account) {
        account.setOpeningDate(LocalDateTime.now());
        account.setLastTransactionDate(LocalDateTime.now());
        return accountRepository.save(account);
    }

    public Mono<Account> update(String id, Account account) {
        return accountRepository.findById(id)
                .flatMap(existingAccount -> {
                    if(account.getBalance() != 0){
                        existingAccount.setBalance(existingAccount.getBalance() + account.getBalance());
                    }
                    if(account.getMaintenanceFee() != null){
                        existingAccount.setMaintenanceFee(account.getMaintenanceFee());
                    }
                    if(account.getMonthlyTransactionLimit() != null){
                        existingAccount.setMonthlyTransactionLimit(account.getMonthlyTransactionLimit());
                    }
                    if(account.getAllowedDayOfMonth() != null){
                        existingAccount.setAllowedDayOfMonth(account.getAllowedDayOfMonth());
                    }
                    if(account.getTitulares() != null){
                        existingAccount.setTitulares(account.getTitulares());
                    }
                    if(account.getAuthorizedSigners() != null){
                        existingAccount.setAuthorizedSigners(account.getAuthorizedSigners());
                    }

                    return accountRepository.save(existingAccount);
                });
    }

    public Mono<Void> delete(String id) {
        return accountRepository.deleteById(id);
    }

    private void validaciones(AccountDto request){
        if(request.getOpeningAmount() < 0){
            throw new RuntimeException("Monto de apertura de cuenta debe ser minimo de 0");
        }
        if(request.getType() == AccountType.AHORRO){
            if(request.getMaintenanceFee() != null && request.getMaintenanceFee() > 0){
                throw new RuntimeException("Cuenta de ahorro esta libre de comisión por mantenimiento");
            }
            if(request.getMonthlyTransactionLimit() == null || request.getMonthlyTransactionLimit() == 0){
                throw new RuntimeException("Cuenta de ahorro debe tener un limite maximo de movimientos mensual");
            }


        }
        if(request.getType() == AccountType.CUENTA_CORRIENTE){
            if(request.getMonthlyTransactionLimit() > 0 ){
                throw new RuntimeException("Cuenta corriente no debe tener limite de movimientos");
            }

        }
        if(request.getType() == AccountType.PLAZO_FIJO){
            if(request.getMaintenanceFee() != null && request.getMaintenanceFee() > 0){
                throw new RuntimeException("Cuenta Plazo fijo debe estar libre de comisión por mantenimiento");
            }
            if(request.getMonthlyTransactionLimit() == null || request.getMonthlyTransactionLimit() != 1){
                throw new RuntimeException("Cuenta Plazo fijo solo puede hacer una transaccion al mes");
            }
            if(request.getAllowedDayOfMonth() == null || request.getAllowedDayOfMonth() == 0){
                throw new RuntimeException("Cuenta Plazo se debe asignar un dia para realizar cualquier transaction");
            }
        }
    }
}
