package network.elrond.account;

import network.elrond.trie.Trie;
import network.elrond.trie.TrieImpl;

import java.io.IOException;

public class AccountsPersistenceUnit<K extends AccountAddress, S extends AccountState> extends AbstractPersistenceUnit<K, S> {

    final Trie trie;

    public AccountsPersistenceUnit(String databasePath) throws IOException {
        super(databasePath);
        trie = new TrieImpl(database);
    }

    @Override
    public void put(byte[] key, byte[] val) {
        trie.update(key, val);
    }

    @Override
    public byte[] get(byte[] key) {
        return trie.get(key);
    }

    public void delete(byte[] key) {
        trie.delete(key);
    }

    public void commit() {
        trie.sync();
    }

    public void rollBack() {
        trie.undo();
    }

    public void cleanCache() {
        ((TrieImpl) trie).cleanCache();
    }

    public TrieImpl copyTrie() {
        return ((TrieImpl) trie).copy();
    }

    public byte[] getRootHash() {
        return trie.getRootHash();
    }
}