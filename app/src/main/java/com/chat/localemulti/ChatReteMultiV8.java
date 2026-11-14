package com.chat.localemulti;

import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ChatReteMultiV8 {
    private static final byte T_HELLO  = 1;
    private static final byte T_TESTO  = 2;
    private static final byte T_BYE    = 7;
    private static final byte T_PRIV   = 8;
    private static final byte T_PING   = 9;

    public interface ChatEventListener {
        void onStato(String messaggio);
        void onErrore(String errore);
        void onConnesso(String nome, String ip);
        void onDisconnesso(String nome, String ip);
        void onTestoRicevuto(String nome, String testo);
        void onPrivatoRicevuto(String nome, String testo);
    }

    public static class Peer {
        public Socket socket;
        public InputStream streamIn;
        public OutputStream streamOut;
        public String nome = "Sconosciuto";
        public String ip = "";
        public final Object writeLock = new Object();
    }

    private final List<Peer> peers = new ArrayList<Peer>();
    private final HashSet<String> inConnessione = new HashSet<String>();
    private final Object lockPeers = new Object();

    private ServerSocket serverSocket;
    private volatile boolean inAscolto = false;
    private String mioNome = "";
    private String codiceSegreto = "";
    private ChatEventListener listener;

    public ChatReteMultiV8(ChatEventListener listener) {
        this.listener = listener;
    }

    public void avviaServer(final int porta, String nome, String codice) {
        chiudiTutto();
        this.mioNome = nome;
        this.codiceSegreto = codice;
        this.inAscolto = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(porta);
                    notificaStato("In ascolto sulla porta " + porta + "... Pronto.");
                    
                    while (inAscolto) {
                        final Socket clientSocket = serverSocket.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                gestisciNuovoClient(clientSocket);
                            }
                        }).start();
                    }
                } catch (IOException e) {
                    if (inAscolto) {
                        notificaErrore("Errore server: " + e.getMessage());
                    }
                }
            }
        }).start();
    }

    private void gestisciNuovoClient(Socket s) {
        try {
            if (!inAscolto) { s.close(); return; }
            s.setTcpNoDelay(true);
            s.setSoLinger(true, 0);
            s.setSoTimeout(5000);
            
            final String remoteIp = s.getInetAddress().getHostAddress();

            synchronized (lockPeers) {
                for (Peer p : peers) {
                    if (p.ip.equals(remoteIp)) {
                        s.close();
                        return;
                    }
                }
            }

            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String[] hello = leggiHello(in);
            if (hello == null || !hello[1].equals(codiceSegreto) || !inAscolto) {
                s.close();
                return;
            }

            byte[] risposta = (mioNome + "\n" + codiceSegreto).getBytes(Charset.forName("UTF-8"));
            scriviFrame(out, T_HELLO, risposta);
            s.setSoTimeout(0);

            Peer p = new Peer();
            p.socket = s;
            p.streamIn = in;
            p.streamOut = out;
            p.nome = hello[0];
            p.ip = remoteIp;

            if (!registraPeerSeUnico(p)) {
                s.close();
                return;
            }

            if (listener != null) listener.onConnesso(p.nome, p.ip);
            cicloRicezione(p);

        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    public void connettiA(final String host, final int porta) {
        if (!inAscolto || host == null || host.isEmpty()) return;

        synchronized (lockPeers) {
            for (Peer p : peers) {
                if (p.ip.equals(host)) return;
            }
            if (inConnessione.contains(host)) return;
            inConnessione.add(host);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket s = new Socket();
                try {
                    s.setSoLinger(true, 0);
                    s.connect(new java.net.InetSocketAddress(host, porta), 2000);
                    if (!inAscolto) { s.close(); return; }

                    s.setTcpNoDelay(true);
                    s.setSoTimeout(5000);
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();

                    byte[] mioHello = (mioNome + "\n" + codiceSegreto).getBytes(Charset.forName("UTF-8"));
                    scriviFrame(out, T_HELLO, mioHello);

                    String[] hello = leggiHello(in);
                    if (hello == null || !hello[1].equals(codiceSegreto) || !inAscolto) {
                        s.close();
                        return;
                    }
                    s.setSoTimeout(0);

                    Peer p = new Peer();
                    p.socket = s;
                    p.streamIn = in;
                    p.streamOut = out;
                    p.nome = hello[0];
                    p.ip = host;

                    if (!registraPeerSeUnico(p)) {
                        s.close();
                        return;
                    }

                    if (listener != null) listener.onConnesso(p.nome, p.ip);
                    cicloRicezione(p);

                } catch (Exception e) {
                    try { s.close(); } catch (IOException ignored) {}
                } finally {
                    synchronized (lockPeers) {
                        inConnessione.remove(host);
                    }
                }
            }
        }).start();
    }

    private boolean registraPeerSeUnico(Peer nuovo) {
        synchronized (lockPeers) {
            if (!inAscolto) return false;
            for (Peer p : peers) {
                if (p.ip.equals(nuovo.ip)) return false;
            }
            peers.add(nuovo);
            return true;
        }
    }

    private void cicloRicezione(Peer p) {
        byte[] testaFrame = new byte[5];
        try {
            while (inAscolto) {
                if (!leggiEsatto(p.streamIn, testaFrame, 5)) break;
                byte tipo = testaFrame[0];
                int len = (testaFrame[1] & 0xFF) | ((testaFrame[2] & 0xFF) << 8) | 
                          ((testaFrame[3] & 0xFF) << 16) | ((testaFrame[4] & 0xFF) << 24);

                if (len < 0 || len > 70000) break;
                byte[] dati = new byte[len];
                if (len > 0 && !leggiEsatto(p.streamIn, dati, len)) break;

                if (tipo == T_PING) {
                    continue;
                } else if (tipo == T_BYE) {
                    break;
                } else if (tipo == T_TESTO) {
                    String msg = new String(dati, Charset.forName("UTF-8"));
                    if (listener != null) listener.onTestoRicevuto(p.nome, msg);
                } else if (tipo == T_PRIV) {
                    String msg = new String(dati, Charset.forName("UTF-8"));
                    if (listener != null) listener.onPrivatoRicevuto(p.nome, msg);
                }
            }
        } catch (Exception ignored) {}

        boolean rimosso;
        synchronized (lockPeers) {
            rimosso = peers.remove(p);
        }
        try { p.socket.close(); } catch (IOException ignored) {}
        if (rimosso && listener != null) {
            listener.onDisconnesso(p.nome, p.ip);
        }
    }

    public void inviaTestoATutti(String testo) {
        byte[] b = testo.getBytes(Charset.forName("UTF-8"));
        List<Peer> lista = new ArrayList<Peer>();
        synchronized (lockPeers) { lista.addAll(peers); }

        for (Peer p : lista) {
            try {
                synchronized (p.writeLock) {
                    scriviFrame(p.streamOut, T_TESTO, b);
                }
            } catch (IOException e) {
                try { p.socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    public void inviaPingATutti() {
        if (!inAscolto) return;
        List<Peer> lista = new ArrayList<Peer>();
        synchronized (lockPeers) { lista.addAll(peers); }
        for (Peer p : lista) {
            try {
                synchronized (p.writeLock) {
                    scriviFrame(p.streamOut, T_PING, null);
                }
            } catch (IOException e) {
                try { p.socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    public void chiudiTutto() {
        inAscolto = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}

        List<Peer> daChiudere = new ArrayList<Peer>();
        synchronized (lockPeers) {
            daChiudere.addAll(peers);
            peers.clear();
            inConnessione.clear();
        }

        for (Peer p : daChiudere) {
            try {
                synchronized (p.writeLock) {
                    scriviFrame(p.streamOut, T_BYE, null);
                }
                p.streamOut.flush();
            } catch (Exception ignored) {}
            try { p.socket.close(); } catch (Exception ignored) {}
        }
    }

    private void scriviFrame(OutputStream out, byte tipo, byte[] dati) throws IOException {
        int len = (dati == null) ? 0 : dati.length;
        byte[] head = new byte[5];
        head[0] = tipo;
        head[1] = (byte) (len & 0xFF);
        head[2] = (byte) ((len >> 8) & 0xFF);
        head[3] = (byte) ((len >> 16) & 0xFF);
        head[4] = (byte) ((len >> 24) & 0xFF);
        out.write(head, 0, 5);
        if (len > 0) out.write(dati, 0, len);
        out.flush();
    }

    private boolean leggiEsatto(InputStream in, byte[] buf, int n) throws IOException {
        int tot = 0;
        while (tot < n) {
            int r = in.read(buf, tot, n - tot);
            if (r <= 0) return false;
            tot += r;
        }
        return true;
    }

    private String[] leggiHello(InputStream in) throws IOException {
        byte[] head = new byte[5];
        if (!leggiEsatto(in, head, 5) || head[0] != T_HELLO) return null;
        int len = (head[1] & 0xFF) | ((head[2] & 0xFF) << 8) | ((head[3] & 0xFF) << 16) | ((head[4] & 0xFF) << 24);
        if (len < 0 || len > 1024) return null;
        byte[] dati = new byte[len];
        if (!leggiEsatto(in, dati, len)) return null;
        String[] parti = new String(dati, Charset.forName("UTF-8")).split("\n", 2);
        return new String[] { parti.length > 0 ? parti[0] : "Sconosciuto", parti.length > 1 ? parti[1] : "" };
    }

    private void notificaStato(final String msg) { if (listener != null) listener.onStato(msg); }
    private void notificaErrore(final String err) { if (listener != null) listener.onErrore(err); }
}
