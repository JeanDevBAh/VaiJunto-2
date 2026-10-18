package config;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Config {
    public final String empresaId;
    public final int portaTcp;
    public final int portaHttp;
    public final String dataDir;
    public final Map<String, String> peers;

    private Config(String empresaId, int portaTcp, int portaHttp, String dataDir, 
                    Map<String, String> peers) {
        this.empresaId = empresaId;
        this.portaTcp = portaTcp;
        this.portaHttp = portaHttp;
        this.dataDir = dataDir;
        this.peers = peers;             
    }

    public static Config doAmbiente() {
        return de(System.getenv());
    }

    static Config de(Map<String, String> env) {
        String id = env.get("EMPRESA_ID");
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException(
                "EMPRESA_ID é obrigatório e só pode ter letras, números, '_' e '-'. Valor: " + id);
        }
        return new Config(
            id,
            Integer.parseInt(env.getOrDefault("PORTA_TCP", "8080")),
            Integer.parseInt(env.getOrDefault("PORTA_HTTP", "7070")),
            env.getOrDefault("DATA_DIR", "data"),
            lerPeers(env.get("PEERS"), id));
    }

     static Map<String, String> lerPeers(String texto, String meuId) {
        Map<String, String> peers = new LinkedHashMap<>();
        if (texto == null || texto.isBlank()) return peers;
        for (String item : texto.split(",")) {
            String[] partes = item.trim().split("=", 2);
            if (partes.length != 2 || partes[0].isBlank() || !partes[1].startsWith("http")) {
                throw new IllegalStateException("PEERS inválido: '" + item + "'. Use ID=http://host:porta");
            }
            String peerId = partes[0].trim();
            if (peerId.equals(meuId)) continue;  // ignora a si mesmo (veja a dica abaixo)
            peers.put(peerId, partes[1].trim().replaceAll("/+$", ""));
        }
        return peers;
    }

    @Override
    public String toString() {
        return "Config{empresa=" + empresaId + ", tcp=" + portaTcp + ", http=" + portaHttp
            + ", dados=" + dataDir + ", peers=" + peers + "}";
    }
}

