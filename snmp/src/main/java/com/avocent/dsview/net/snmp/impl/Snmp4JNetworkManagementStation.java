package com.avocent.dsview.net.snmp.impl;

import com.avocent.dsview.net.snmp.*;
import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.MessageProcessingModel;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.*;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.snmp4j.mp.SnmpConstants.SNMP_ERROR_GENERAL_ERROR;

/**
 * Created by zshatzov on 4/19/2016.
 *
 *
 * <p>
 *     An implementation of a <code>SNMP Network Management Station</code>.
 *     This implementation uses an open source library <em>SNMP4J</em> to perform
 *     GET/SET request on a SNMP agent.
 * </p>
 */
public class Snmp4JNetworkManagementStation implements NetworkManagementStation{

    private static final Logger LOGGER = Logger.getLogger(Snmp4JNetworkManagementStation.class.getName());

    /**
     * <p>Perform a synchronous SNMPv1 GET request</p>
     *
     * @param binding An object that encapsulates the variable binding and agent info
     * @return SnmpV1Response
     */
    @Override
    public SnmpV1Response getSnmpV1(final SnmpGetV1RequestBinding binding){

        LOGGER.finest("Process Get SNMPv1 request");

        if (isNull(binding.getHost()) || binding.getHost().isEmpty()) {
            LOGGER.severe("Host is null or empty");
            throw new SnmpGetException("Host is missing");
        }

        final TransportMapping<UdpAddress> transport;
        final Snmp snmp;
        try {
            transport = new DefaultUdpTransportMapping();
            snmp = new Snmp(transport);
            transport.listen();
        }catch (IOException e){
            LOGGER.log(Level.SEVERE, "GET SNMPv1 transport configuration failed", e);
            throw new SnmpGetException("Failed to configure UDP transport", e);
        }

        final String address = String.format("udp:%s/161", binding.getHost());
        final CommunityTarget target = createCommunityTarget(address, binding.getCommunityString());

        PDU pdu = new PDU();
        pdu.add(new VariableBinding(new OID(binding.getOid())));

        try {
            ResponseEvent event = snmp.get(pdu, target);
            String requestId = event.getResponse().getRequestID().toString();
            int errorStatusCode = event.getResponse().getErrorStatus();
            String errorStatusMessage = event.getResponse().getErrorStatusText();
            SnmpV1Response response = new SnmpV1Response(binding.getClientId(),
                    requestId, errorStatusMessage, errorStatusCode);
            for(VariableBinding vb: event.getResponse().getVariableBindings()){
                String oid  = vb.getOid().toString();
                String value = vb.toValueString();
                String type = vb.getVariable().getSyntaxString();
                response.addVariableBinding(oid, value, type);
            }

            LOGGER.finest("Successfully processed SNMPv1 get request");

            return response;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "SNMPv1 get request failed", e);
            throw new SnmpGetException("SNMPv1 get request failed", e);
        }finally {
            if(nonNull(snmp)){
                try {
                    snmp.close();
                } catch (IOException IGNORE) {
                }
            }
        }
    }

    /**
     * <p>Perform a synchronous SNMPv3 GET request</p>
     *
     * @param binding An object that encapsulates the variable binding and agent info
     * @return SnmpV3Response
     */
    @Override
    public SnmpV3Response getSnmpV3(final SnmpGetV3RequestBinding binding){

        if (isNull(binding.getHost()) || binding.getHost().isEmpty()) {
            throw new SnmpGetException("Host is missing");
        }

        final TransportMapping<UdpAddress> transport;
        final Snmp snmp;
        final USM usm;
        try {
            transport = new DefaultUdpTransportMapping();
            snmp = new Snmp(transport);
            if(nonNull(binding.getEngineID())){
                usm = new USM(SecurityProtocols.getInstance(),
                        new OctetString(binding.getEngineID()), 0);
            }else {
                LOGGER.finest("Create USM with default local engine ID");
                usm = new USM(SecurityProtocols.getInstance(),
                        new OctetString(MPv3.createLocalEngineID()), 0);
            }
            SecurityModels.getInstance().addSecurityModel(usm);
            transport.listen();
        }catch (IOException e){
            throw new SnmpGetException("Failed to configure UDP transport", e);
        }

        final String address = String.format("udp:%s/161", binding.getHost());

        final UserTarget target;
        final ScopedPDU pdu;
        try{
            target = createUserTarget(address, binding.getUserSecurityModel());

            pdu = new ScopedPDU();
            pdu.add(new VariableBinding(new OID(binding.getOid())));

            if(nonNull(binding.getUserSecurityModel())) {
                final UsmUser usmUser = createUsmUser(binding.getUserSecurityModel());
                final OctetString userName = nonNull(binding.getUserSecurityModel().getUserName())?
                        new OctetString(binding.getUserSecurityModel().getUserName()): null;
                snmp.getUSM().addUser(userName, usmUser);
            }

            ResponseEvent event = snmp.get(pdu, target);
            final String requestId;
            final int errorStatusCode;
            final String errorStatusMessage;
            if(nonNull(event) && nonNull(event.getResponse())) {
                requestId = nonNull(event.getResponse().getRequestID()) ?
                        event.getResponse().getRequestID().toString() : null;
                errorStatusCode = event.getResponse().getErrorStatus();
                errorStatusMessage = event.getResponse().getErrorStatusText();
            }else{
                requestId = null;
                errorStatusCode = SNMP_ERROR_GENERAL_ERROR;
                errorStatusMessage = String.format(
                     "Failed to retrieve variable binding for host (%s) and OID (%s)",
                        binding.getHost(), binding.getOid());
            }

            SnmpV3Response response = new SnmpV3Response(usm.getLocalEngineID().toString(),
                  binding.getClientId(), requestId, errorStatusMessage, errorStatusCode);

            if(nonNull(event) && nonNull(event.getResponse())) {
                for (VariableBinding vb : event.getResponse().getVariableBindings()) {
                    String oid = vb.getOid().toString();
                    String value = vb.toValueString();
                    String type = vb.getVariable().getSyntaxString();
                    response.addVariableBinding(oid, value, type);
                }
            }
            return response;
        }catch(IOException e){
            throw new SnmpGetException("SnmpV3 get request failed", e);
        }finally {
            if(nonNull(snmp)){
                try {
                    snmp.close();
                } catch (IOException IGNORE) {
                }
            }
        }
    }

    /**
     * <p>A method that processes multiple asynchronous SNMPv1 GET requests</p>
     *
     * @param listener A callback component that will be invoked once the asynchronous SNMPv1 GET request are processed
     * @param bindings One or more SNMPv1 request to be processed
     */
    @Override
    public void getSnmpV1Async(final SnmpGetEventListener<SnmpV1Response> listener,
                               final Stream<SnmpGetV1RequestBinding> bindings) {

        LOGGER.finest("Process async SNMPv1 requests");

        List<SnmpV1Response> responses =
                bindings.map(binding-> prepareAsyncCall(binding, this::getSnmpV1))
                        .map(this::getAsyncResponse)
                        .collect(Collectors.toList());

        listener.process(responses.stream());
    }

    /**
     * <p>A method that processes multiple asynchronous SNMPv3 GET requests</p>
     *
     * @param listener A callback component that will be invoked once the asynchronous SNMPv3 GET request are processed
     * @param bindings One or more SNMPv3 GET request to be processed
     */
    @Override
    public void getSnmpV3Async(final SnmpGetEventListener<SnmpV3Response> listener,
                               final Stream<SnmpGetV3RequestBinding> bindings) {
        LOGGER.finest("Process async SNMPv3 requests");

        List<SnmpV3Response> responses =
                bindings.map(binding-> prepareAsyncCall(binding, this::getSnmpV3))
                        .map(this::getAsyncResponse)
                        .collect(Collectors.toList());

        listener.process(responses.stream());
    }

    @Override
    public SnmpV1Response setSnmpV1(SnmpSetV1RequestBinding binding) {
        LOGGER.finest("Process Get SNMPv1 request");

        if (isNull(binding.getHost()) || binding.getHost().isEmpty()) {
            LOGGER.severe("Host is null or empty");
            throw new SnmpGetException("Host is missing");
        }

        final TransportMapping<UdpAddress> transport;
        final Snmp snmp;
        try {
            transport = new DefaultUdpTransportMapping();
            snmp = new Snmp(transport);
            transport.listen();
        }catch (IOException e){
            LOGGER.log(Level.SEVERE, "SET SNMPv1 transport configuration failed", e);
            throw new SnmpSetException("Failed to configure UDP transport", e);
        }

        final String address = String.format("udp:%s/161", binding.getHost());
        final CommunityTarget target = createCommunityTarget(address, binding.getCommunityString());

        PDU pdu = new PDU();
        VariableBinding setVB = new VariableBinding(new OID(binding.getVariableBinding().getOid()));
        setVB.setVariable(translateVariable(binding.getVariableBinding()));
        pdu.add(setVB);

        try {
            ResponseEvent event = snmp.set(pdu, target);
            String requestId = event.getResponse().getRequestID().toString();
            int errorStatusCode = event.getResponse().getErrorStatus();
            String errorStatusMessage = event.getResponse().getErrorStatusText();
            SnmpV1Response response = new SnmpV1Response(binding.getClientId(),
                    requestId, errorStatusMessage, errorStatusCode);
            for(VariableBinding vb: event.getResponse().getVariableBindings()){
                String oid  = vb.getOid().toString();
                String value = vb.toValueString();
                String type = vb.getVariable().getSyntaxString();
                response.addVariableBinding(oid, value, type);
            }

            LOGGER.finest("Successfully processed SNMPv1 SET request");

            return response;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "SNMPv1 get request failed", e);
            throw new SnmpGetException("SNMPv1 get request failed", e);
        }finally {
            if(nonNull(snmp)){
                try {
                    snmp.close();
                } catch (IOException IGNORE) {
                }
            }
        }
    }

    @Override
    public SnmpV3Response setSnmpV3(SnmpSetV3RequestBinding binding) {
        throw new UnsupportedOperationException("Code not implemented yet");
    }

    private <T extends SnmpResponse> T getAsyncResponse(CompletableFuture<T> future){
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new SnmpGetException("Failed to retrieve async GET response", e);
        }
    }

    private <T extends SnmpResponse, U extends SnmpGetRequestBinding>
            CompletableFuture<T> prepareAsyncCall(U binding, Function<U, T> handler){
        CompletableFuture<T> completableFuture =
                CompletableFuture.supplyAsync(() -> {return handler.apply(binding);});

        return completableFuture;
    }

    private CommunityTarget createCommunityTarget(String address, String communityString){
        CommunityTarget target = new CommunityTarget(GenericAddress.parse(address),
                new OctetString(communityString));
        target.setRetries(2);
        target.setTimeout(1000);
        target.setVersion(SnmpConstants.version1);

        return target;
    }

    private UserTarget createUserTarget(String address, UserSecurityModel usm){

        final UserTarget target = new UserTarget();
        target.setAddress(GenericAddress.parse(address));
        target.setRetries(3);
        target.setTimeout(1500);
        target.setVersion(SnmpConstants.version3);
        target.setSecurityModel(MessageProcessingModel.MPv3);

        if(nonNull(usm) && nonNull(usm.getSecurityLevel())) {
            target.setSecurityLevel(usm.getSecurityLevel().ordinal());
        }

        if(nonNull(usm) && nonNull(usm.getSecurityName())){
            target.setSecurityName(
                    new OctetString(usm.getSecurityName()));
        }

        return target;
    }

    private UsmUser createUsmUser(final UserSecurityModel usm){
        LOGGER.finest("Setup USM user credetinals to establish secure communications");
        final OctetString securityName = nonNull(usm.getSecurityName())?
                new OctetString(usm.getSecurityName()): null;
        OID authProtocol =  null;
        if(nonNull(usm.getAuthenticationProtocol())) {
            switch(usm.getAuthenticationProtocol()){
                case MD5: authProtocol = AuthMD5.ID; break;
                case SHA: authProtocol = AuthSHA.ID; break;
            }
        }
        final OctetString authPassphrase = nonNull(usm.getAuthenticationPassphrase())?
                new OctetString(usm.getAuthenticationPassphrase()): null;
        OID privProtocol = null;
        if(nonNull(usm.getPrivacyProtocol())) {
            switch (usm.getPrivacyProtocol()){
                case AES128: privProtocol = PrivAES128.ID; break;
                case AES192: privProtocol = PrivAES192.ID; break;
                case AES256: privProtocol = PrivAES256.ID; break;
                case DES: privProtocol = PrivDES.ID; break;
            }
        }
        final OctetString privPassphrase = nonNull(usm.getPrivacyPassphrase())?
                new OctetString(usm.getPrivacyPassphrase()): null;

        return new UsmUser(securityName, authProtocol, authPassphrase,
                privProtocol,privPassphrase);
    }

    private Variable translateVariable(SnmpSetVariableBinding binding){
         switch (binding.getVariableType()){
             case Counter32: return new Counter32(Integer.valueOf(binding.getValue()));
             case Counter64: return new Counter64(Long.valueOf(binding.getValue()));
             case Gauge32: return new Gauge32(Integer.valueOf(binding.getValue()));
             case Integer32: return new Integer32(Integer.valueOf(binding.getValue()));
             case IpAddress: return new IpAddress(binding.getValue());
             case OctetString: return new OctetString(binding.getValue());
             case OID: return new OID(binding.getValue());
             case Opaque: return new Opaque(binding.getValue().getBytes());
             case SshAddress: return new SshAddress(binding.getValue());
             case TcpAddress: return new TcpAddress(binding.getValue());
             case TimeTicks: return new TimeTicks(Long.valueOf(binding.getValue()));
             case TlsAddress: return new TlsAddress(binding.getValue());
             case UdpAddress: return new UdpAddress(binding.getValue());
             case UnsignedInteger32: return new UnsignedInteger32(Math.abs(Integer.valueOf(binding.getValue())));
             default: throw new SnmpSetException("Invalid variable type for SET request");
         }
    }
}