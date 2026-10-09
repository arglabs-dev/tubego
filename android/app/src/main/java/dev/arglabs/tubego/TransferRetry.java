package dev.arglabs.tubego;

import java.io.*;
import java.net.*;

/** Pure durable retry budget. A pause is never a failed attempt. */
public final class TransferRetry {
    private TransferRetry(){}
    public static final long[] DELAYS={30000,120000,300000};
    public static final class Failure extends IOException {
        public final String code;public final boolean transientFailure;
        public Failure(String code,boolean retryable){super("Transferencia: "+code);this.code=code;this.transientFailure=retryable;}
    }
    public static boolean transientError(Exception error){
        if(error instanceof Failure)return ((Failure)error).transientFailure;
        if(error instanceof ApiClient.ApiException){int status=((ApiClient.ApiException)error).status;return status==429||status>=500&&status<=599;}
        return error instanceof SocketTimeoutException||error instanceof ConnectException||error instanceof UnknownHostException
            ||error instanceof SocketException||error instanceof EOFException;
    }
    public static void failed(TransferRecord record,Exception error,long now)throws IOException{
        record.pauseReason="";record.failures++;
        record.failureCode=error instanceof Failure?((Failure)error).code:transientError(error)?"temporary_network_failure":"transfer_rejected";
        if(transientError(error)&&record.failures<=DELAYS.length){
            record.nextRetryAt=now+DELAYS[record.failures-1];record.state="retry_wait";
            record.message="Fallo temporal. Reintento automático "+record.failures+" de 3 pendiente.";
        }else{record.nextRetryAt=0;record.state="failed";record.message="No se pudo completar la descarga ("+record.failureCode+"). Puedes reintentar manualmente.";}
        record.save();
    }
    public static void reset(TransferRecord record)throws IOException{record.pauseReason="";record.failures=0;record.nextRetryAt=0;record.failureCode="";record.state=record.media().isFile()?"complete":"pending";record.message="";record.save();}
}
