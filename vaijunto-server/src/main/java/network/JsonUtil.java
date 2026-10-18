package network;

import com.google.gson.Gson;
import java.lang.reflect.Type;
/**
 * Classe utilitária responsável pela conversão entre objetos Java e JSON.
 *
 * Centraliza o uso da biblioteca Gson para serializar DTOs antes do envio
 * pela rede e desserializar mensagens recebidas pelo servidor.
 *
 * A classe evita que cada componente precise implementar individualmente
 * as regras de conversão do protocolo.
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