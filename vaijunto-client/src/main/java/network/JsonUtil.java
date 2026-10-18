package network;

import com.google.gson.Gson;
import java.lang.reflect.Type;
/**
 * Utilitário responsável pela serialização e desserialização de mensagens JSON
 * no cliente.
 *
 * Converte DTORequest em texto antes do envio e transforma as respostas JSON
 * recebidas em DTOResponse.
 */
public class JsonUtil {
    private static final Gson gson = new Gson();

    public static String paraJson(Object objeto){
        return gson.toJson(objeto);
    }

    // Mantém o original para objetos simples
    public static <T> T paraObject(String json, Class<T> classe){
        return gson.fromJson(json, classe);
    }

    // NOVO: Permite desserializar tipos complexos, como DTOResponse<List<Itinerario>>
    public static <T> T paraObjectComplexo(String json, Type tipoComplexo){
        return gson.fromJson(json, tipoComplexo);
    }
}