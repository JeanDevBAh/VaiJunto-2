package controller;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import model.TipoUser;
import model.Usuario;
import wal.Evento;
import wal.Wal;
/**
 * Gerencia usuários cadastrados e sessões ativas.
 *
 * Mantém um mapa de usuários identificados pelo login e outro mapa que associa
 * tokens de autenticação aos usuários atualmente conectados.
 *
 * É responsável por cadastrar usuários, validar login e senha, criar tokens,
 * verificar sessões, realizar logout e alterar o tipo de usuário.
 *
 * Utiliza ConcurrentHashMap para permitir acesso seguro por múltiplas threads.
 */
public class UserService {
    
    private final Map<String, Usuario> usuarios;
    private final Map<String, Usuario> ativos;

    // Null = sem log (tudo só em memória, como no Problema 1)
    private final Wal wal;

    public UserService(){
        this(null);
    }

    public UserService(Wal wal){
        this.usuarios = new ConcurrentHashMap<>();
        this.ativos = new ConcurrentHashMap<>();
        this.wal = wal;
    }

    // ---------- replay (usado só na recuperação; NÃO grava no log) ----------

    public void aplicarCadastro(Evento e) {
        TipoUser tipo = e.tipoUser == null ? null : TipoUser.valueOf(e.tipoUser);
        usuarios.put(e.login, new Usuario(e.login, e.senha, e.usuarioId, tipo));
    }

    public void aplicarMudancaTipo(Evento e) {
        Usuario u = usuarios.get(e.login);
        if (u != null) {
            u.setTipoUser(TipoUser.valueOf(e.tipoUser));
        }
    }

    public Usuario buscarPorLogin(String login) {
        return login == null ? null : usuarios.get(login);
    }

    // ---------- operações normais: validar -> registrar no log -> aplicar ----------

    public synchronized boolean cadastrarUsuario(String login, String senha, TipoUser tipo){
        if (usuarios.containsKey(login)) {
            return false;
        }
        Usuario newUser = new Usuario(login, senha, tipo); // o id (UUID) é gerado aqui
        if (wal != null) {
            wal.adicionar(Evento.usuarioCadastrado(newUser)); // se falhar, nada foi alterado
        }
        usuarios.put(login, newUser);
        return true;
    }

    public String fazerLogin(String login, String senha){
        Usuario user = usuarios.get(login);
        if(user != null && user.getSenha().equals(senha)){
            String token = UUID.randomUUID().toString();
            this.ativos.put(token, user);
            return token;
        }
        return null;
    }

    public  Usuario validarSessao(String token){
        if(token==null){
            return null; 
        }
        Usuario user = ativos.get(token);
        if(user!=null) {
            return user;
        }
        return null;
    }


    public boolean logout(String token){
        if (token == null){return false;}
        Usuario removido = ativos.remove(token);
        return removido != null;
    }

    public synchronized boolean mudaTipoUser(String token, TipoUser tiponovo){
        Usuario user = ativos.get(token);
        if(user != null && tiponovo != null){
            if (wal != null) {
                wal.adicionar(Evento.usuarioTipoAlterado(user.getLogin(), tiponovo.name()));
            }
            user.setTipoUser(tiponovo);
            return true;
        }
        return false;
    }
}