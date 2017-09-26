/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved  
 *  
 * Written by Sergey Chernov <real.sergeych@gmail.com>, August 2017.
 *
 */

package com.icodici.universa.contract;

import com.icodici.crypto.EncryptionError;
import com.icodici.crypto.PrivateKey;
import com.icodici.crypto.PublicKey;
import com.icodici.universa.Approvable;
import com.icodici.universa.ErrorRecord;
import com.icodici.universa.Errors;
import com.icodici.universa.HashId;
import com.icodici.universa.contract.permissions.ChangeOwnerPermission;
import com.icodici.universa.contract.permissions.Permission;
import com.icodici.universa.contract.roles.Role;
import com.icodici.universa.contract.roles.RoleLink;
import com.icodici.universa.contract.roles.SimpleRole;
import net.sergeych.biserializer.*;
import net.sergeych.boss.Boss;
import net.sergeych.collections.Multimap;
import net.sergeych.tools.Binder;
import net.sergeych.tools.Do;
import net.sergeych.utils.Bytes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.yaml.snakeyaml.Yaml;

import java.io.FileReader;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.icodici.universa.Errors.*;
import static java.util.Arrays.asList;

@BiType(name = "UniversaContract")
public class Contract implements Approvable, BiSerializable, Cloneable {

    private final Set<HashId> referencedItems = new HashSet<>();
    private final Set<Approvable> revokingItems = new HashSet<>();
    private final Set<Contract> newItems = new HashSet<>();
    private Definition definition;
    private final Map<String, Role> roles = new HashMap<>();
    private State state;
    private byte[] sealedBinary;
    private int apiLevel = 2;

    /**
     * true if the contract was imported from sealed capsule
     */
    private boolean isSealed = false;
    private final Map<PublicKey, ExtendedSignature> sealedByKeys = new HashMap<>();
    private Set<PrivateKey> keysToSignWith = new HashSet<>();
    private HashId id;

    /**
     * Extract contract from a sealed form, filling signers information. Only valid signatures are kept, invalid are
     * silently discarded. It is recommended to call {@link #check()} after construction to see the errors.
     *
     * @param sealed binary sealed contract
     *
     * @throws IllegalArgumentException on the various format errors
     */
    public Contract(byte[] sealed) throws IOException {
        this.sealedBinary = sealed;
        Binder data = Boss.unpack(sealed);
        if (!data.getStringOrThrow("type").equals("unicapsule"))
            throw new IllegalArgumentException("wrong object type, unicapsule required");
        if (data.getIntOrThrow("version") > 7)
            throw new IllegalArgumentException("version too high");
        byte[] contractBytes = data.getBinaryOrThrow("data");

        deserialize(Boss.unpack(contractBytes), new BiDeserializer());

//        initializeWithDsl(Boss.unpack(contractBytes));

        HashMap<Bytes, PublicKey> keys = new HashMap<Bytes, PublicKey>();

        roles.values().forEach(role -> {
            role.getKeys().forEach(key -> keys.put(ExtendedSignature.keyId(key), key));
        });

        for (Object signature : (List)data.getOrThrow("signatures")) {
            byte[] s = ((Bytes) signature).toArray();
            Bytes keyId = ExtendedSignature.extractKeyId(s);
            PublicKey key = keys.get(keyId);
            if (key != null) {
                ExtendedSignature es = ExtendedSignature.verify(key, s, contractBytes);
                if (es != null) {
                    sealedByKeys.put(key, es);
                }
            }
        }
    }

    public Contract() {
        definition = new Definition();
        state = new State();
    }

    /**
     * Create a default empty new contract using a provided key as issuer and owner and sealer. Default expiration is
     * set to 5 years.
     * <p>
     * This constructor adds key as sealing signature so it is ready to {@link #seal()} just after construction, thought
     * it is necessary to put real data to it first. It is allowed to change owner, expiration and data fields after
     * creation (but before sealing).
     *
     * @param key
     */
    public Contract(PrivateKey key) {
        this();
        // default expiration date
        setExpiresAt(ZonedDateTime.now().plusYears(5));
        // issuer role is a key for a new contract
        Role r = setIssuerKeys(key.getPublicKey());
        // issuer is owner, link roles
        registerRole(new RoleLink("owner", "issuer"));
        registerRole(new RoleLink("creator", "issuer"));
        // owner can change permission
        addPermission(new ChangeOwnerPermission(r));
        // issuer should sign
        addSignerKey(key);
    }

    public List<ErrorRecord> getErrors() {
        return errors;
    }

    private final List<ErrorRecord> errors = new ArrayList<>();

//    /**
//     * Test use only
//     * @param root
//     * @throws EncryptionError
//     */
//    Contract(Binder root) throws EncryptionError {
//        this();
//        deserialize(root, DefaultBiMapper.getDeserializer());
//    }

    private Contract initializeWithDsl(Binder root) throws EncryptionError {
        apiLevel = root.getIntOrThrow("api_level");
        definition = new Definition().initializeWithDsl(root.getBinder("definition"));
        state = new State().initializeWithDsl(root.getBinder("state"));
        // now we have all roles, we can build permissions:
        definition.scanDslPermissions();
        return this;
    }

    public static Contract fromYamlFile(String fileName) throws IOException {
        Yaml yaml = new Yaml();
        try (FileReader r = new FileReader(fileName)) {
            Binder binder = Binder.from(DefaultBiMapper.deserialize((Map) yaml.load(r)));
            return new Contract().initializeWithDsl(binder);
        }
    }

    public State getState() {
        return state;
    }

    public int getApiLevel() {
        return apiLevel;
    }

    public void setApiLevel(int apiLevel) {
        this.apiLevel = apiLevel;
    }

    @Override
    public Set<HashId> getReferencedItems() {
        return referencedItems;
    }

    @Override
    public Set<Approvable> getRevokingItems() {
        return revokingItems;
    }

    @Override
    public Set<? extends Approvable> getNewItems() {
        return newItems;
    }

    @Override
    public boolean check() {
        try {
            // common check for all cases
            errors.clear();
            basicCheck();
            if (state.origin == null)
                checkRootContract();
            else
                checkChangedContract();
        } catch (Exception e) {
            e.printStackTrace();
            addError(FAILED_CHECK, "", e.toString());
        }
        return errors.size() == 0;
    }

    /**
     * Create new root contract to be created. It may have parent, but does not have origin, as it is an origin itself.
     */
    private void checkRootContract() {
        // root contract must be issued ny the issuer
        Role issuer = getRole("issuer");
        if(issuer == null || !issuer.isValid()) {
            addError(BAD_VALUE, "definition.issuer", "missing issuer");
            return;
        }
        // the bad case - no issuer - should be processed normally without exceptions:
        Role createdBy = getRole("creator");
        if( createdBy == null || !createdBy.isValid() ) {
            addError(BAD_VALUE, "state.created_by", "invalid creator");
            return;
        }
        if (issuer != null && !issuer.equalKeys(createdBy))
            addError(ISSUER_MUST_CREATE, "state.created_by");
        if (state.revision != 1)
            addError(BAD_VALUE, "state.revision", "must be 1 in a root contract");
        if (state.createdAt == null)
            state.createdAt = definition.createdAt;
        else if (!state.createdAt.equals(definition.createdAt))
            addError(BAD_VALUE, "state.created_at", "invalid");
        if (state.origin != null)
            addError(BAD_VALUE, "state.origin", "must be empty in a root contract");

        checkRootDependencies();
    }

    private void checkRootDependencies() {
        // Revoke dependencies: _issuer_ of the root contract must have right to revoke
        revokingItems.forEach(item -> {
            if (!(item instanceof Contract))
                addError(BAD_REF, "revokingItem", "revoking item is not a Contract");
            Contract rc = (Contract) item;
            if (!rc.isPermitted("revoke", getIssuer()))
                addError(FORBIDDEN, "revokingItem", "revocation not permitted for item " + rc.getId());
        });
    }

    public void addError(Errors code, String field, String text) {
        Errors code1 = code;
        String field1 = field;
        String text1 = text;
        errors.add(new ErrorRecord(code1, field1, text1));
    }

    private void checkChangedContract() {
        // get the previous version
        Contract parent = getRevokingItem(getParent());
        if (parent == null) {
            addError(BAD_REF, "parent", "parent contract must be included");
        } else {
            // checking parent:
            // proper origin
            HashId rootId = parent.getRootId();
            if (!rootId.equals(getOrigin())) {
                addError(BAD_VALUE, "state.origin", "wrong origin, should be root");
            }
            if (!getParent().equals(parent.getId()))
                addError(BAD_VALUE, "state.parent", "illegal parent reference");

            ContractDelta delta = new ContractDelta(parent, this);
            delta.check();
        }
        // todo: check state change (chan
//        addError(NOT_SUPPORTED, "");
    }

    /**
     * Get the root id with the proper logic: for the root contract, it returns its {@link #getId()}, for the child
     * contracts - the origin id (state.origin field value). Note that all contract states in the chain share same
     * origin - it is an id of the root contract, whose id is always null.
     *
     * @return id of the root contract.
     */
    protected HashId getRootId() {
        HashId origin = getOrigin();
        return origin == null ? getId() : origin;
    }

    /**
     * Try to find revoking item with a given ID. If the matching item exists but is not a {@link Contract} instance, it
     * will not be found, null will be returned.
     *
     * @param id to find
     *
     * @return matching Contract instance or null if not found.
     */
    private Contract getRevokingItem(HashId id) {
        for (Approvable a : revokingItems) {
            if (a.getId().equals(id) && a instanceof Contract)
                return (Contract) a;
        }
        return null;
    }

    private void basicCheck() {
        if (definition.createdAt == null ||
                definition.createdAt.isAfter(ZonedDateTime.now()) ||
                definition.createdAt.isBefore(getEarliestCreationTime())) {
            addError(BAD_VALUE, "definition.created_at", "invalid");
        }
        if (definition.expiresAt == null ||
                definition.expiresAt.isBefore(ZonedDateTime.now())) {
            addError(EXPIRED, "definition.expires_at");
        }
        if (state.createdAt == null ||
                state.createdAt.isAfter(ZonedDateTime.now()) ||
                state.createdAt.isBefore(getEarliestCreationTime())) {
            addError(BAD_VALUE, "state.created_at");
        }
        if (apiLevel < 1)
            addError(BAD_VALUE, "api_level");
        Role owner = getRole("owner");
        if (owner == null || !owner.isValid())
            addError(MISSING_OWNER, "state.owner");
        Role issuer = getRole("issuer");
        if (issuer == null || !issuer.isValid())
            addError(MISSING_ISSUER, "state.issuer");
        if (state.revision < 1)
            addError(BAD_VALUE, "state.revision");
        Role createdBy = getRole("creator");
        if (createdBy == null || !createdBy.isValid())
            addError(BAD_VALUE, "state.created_by");
        if (!isSignedBy(createdBy))
            addError(NOT_SIGNED, "", "missing creator signature(s)");
    }

    private boolean isSignedBy(Role role) {
        if (role == null)
            return false;
        if (!sealedByKeys.isEmpty())
            return role.isAllowedForKeys(getSealedByKeys());
        return role.isAllowedForKeys(
                getKeysToSignWith()
                        .stream()
                        .map(k -> k.getPublicKey())
                        .collect(Collectors.toSet())

        );
    }

    /**
     * Resolve object describing role and create either: - new role object - symlink to named role instance, ensure it
     * is register and return it, if it is a Map, tries to contruct and register {@link Role} thent return it.
     *
     * @param roleObject
     *
     * @return
     */
    @NonNull
    protected Role createRole(String roleName, Object roleObject) {
        if (roleObject instanceof CharSequence) {
            return registerRole(new RoleLink(roleName, roleObject.toString()));
        }
        if (roleObject instanceof Role)
            return registerRole(((Role) roleObject).linkAs(roleName));
        if (roleObject instanceof Map) {
            Role r = Role.fromDslBinder(roleName, Binder.from(roleObject));
            return registerRole(r);
        }
        throw new IllegalArgumentException("cant make role from " + roleObject);
    }

    public Role getRole(String roleName) {
        return roles.get(roleName);
    }

    @Override
    public HashId getId() {
        if (id == null) {
            if (sealedBinary != null)
                id = new HashId(sealedBinary);
            else
                throw new IllegalStateException("the contract has no binary attached, no Id could be calculated");
        }
        return id;
    }


    public Role getIssuer() {
        // maybe we should cache it
        return getRole("issuer");
    }

    public ZonedDateTime getCreatedAt() {
        return definition.createdAt;
    }

    public ZonedDateTime getExpiresAt() {
        return definition.expiresAt;
    }

    public Map<String, Role> getRoles() {
        return roles;
    }

    public Definition getDefinition() {
        return definition;
    }

    public KeyRecord testGetOwner() {
        return getRole("owner").getKeyRecords().iterator().next();
    }

    public Role registerRole(Role role) {
        String name = role.getName();
        roles.put(name, role);
        role.setContract(this);
        return role;
    }

    public boolean isPermitted(String permissionName, KeyRecord keyRecord) {
        return isPermitted(permissionName, keyRecord.getPublicKey());
    }

    public void addPermission(Permission perm) {
        permissions.put(perm.getName(), perm);
    }

    public boolean isPermitted(String permissionName, PublicKey key) {
        Collection<Permission> cp = permissions.get(permissionName);
        if (cp != null) {
            for (Permission p : cp) {
                if (p.isAllowedForKeys(key))
                    return true;
            }
        }
        return false;
    }

    public boolean isPermitted(String permissionName, Collection<PublicKey> keys) {
        Collection<Permission> cp = permissions.get(permissionName);
        if (cp != null) {
            for (Permission p : cp) {
                if (p.isAllowedForKeys(keys))
                    return true;
            }
        }
        return false;
    }

    public boolean isPermitted(String permissionName, Role role) {
        return isPermitted(permissionName, role.getKeys());
    }

    protected void addError(Errors code, String field) {
        Errors code1 = code;
        String field1 = field;
        errors.add(new ErrorRecord(code1, field1, ""));
    }

    public ChronoZonedDateTime<?> getEarliestCreationTime() {
        return ZonedDateTime.now().minusDays(10);
    }

    public Set<PublicKey> getSealedByKeys() {
        return sealedByKeys.keySet();
    }

    public Set<PrivateKey> getKeysToSignWith() {
        return keysToSignWith;
    }

    public void setKeysToSignWith(Set<PrivateKey> keysToSignWith) {
        this.keysToSignWith = keysToSignWith;
    }

    public void addSignerKeyFromFile(String fileName) throws IOException {
        addSignerKey(new PrivateKey(Do.read(fileName)));
    }

    public void addSignerKey(PrivateKey privateKey) {
        keysToSignWith.add(privateKey);
    }

    /**
     * Important. This method should be invoked after {@link #check()}.
     *
     * @return true if there are no errors detected by now
     */
    public boolean isOk() {
        return errors.isEmpty();
    }

    public byte[] seal() {
        byte[] theContract = Boss.pack(serializeToBinder());
        Binder result = Binder.of("type", "unicapsule", "version", apiLevel, "data", theContract);
        List<byte[]> signatures = new ArrayList<>();
        keysToSignWith.forEach(key -> {
            signatures.add(ExtendedSignature.sign(key, theContract));
        });
        result.put("data", theContract);
        result.put("signatures", signatures);
        setOwnBinary(result);
        return sealedBinary;
    }

    protected Binder serializeToBinder() {
        return DefaultBiMapper.serialize(this);
    }

    /**
     * Get the last knwon packed representation pf the contract. Should be called if the contract was contructed from a
     * packed binary ({@link #Contract(byte[])} or was explicitly sealed {@link #seal()}.
     * <p>
     * Caution. This method could return out of date binary, if the contract was changed after the {@link #seal()} call.
     * Before we will add track of changes, use it only if you are sure that {@link #seal()} was called and contract was
     * not changed since then.
     *
     * @return last result of {@link #seal()} call, or the binary from which was constructed.
     */
    public byte[] getLastSealedBinary() {
        return sealedBinary;
    }

    private void setOwnBinary(Binder result) {
        sealedBinary = Boss.pack(result);
        this.id = HashId.of(sealedBinary);
    }

    /**
     * Create new revision to be changed, signed sealed and then ready to approve. Created "revision" contract is a copy
     * of this contract, with all fields and references correctly set. After this call one need to change mutable
     * fields, add signing keys, seal it and then apss to Universa network for approval.
     *
     * @return new revision of this contract, identical to this one, to be modified.
     */
    public Contract createRevision() {
        try {
            // We need deep copy, so, simple while not that fast.
            // note that revisions are create on clients where speed it not of big importance!
            Contract newRevision = (Contract) clone();
            // modify th edeep copy for a new revision
            newRevision.state.revision = state.revision + 1;
            newRevision.state.createdAt = ZonedDateTime.now();
            newRevision.state.parent = getId();
            newRevision.state.origin = state.revision == 1 ? getId() : state.origin;
            revokingItems.add(this);
            return newRevision;
        } catch (Exception e) {
            throw new IllegalStateException("failed to create revision", e);
        }
    }

    public int getRevision() {
        return state.revision;
    }

    public HashId getParent() {
        return state.parent;
    }

    public HashId getOrigin() {
        return state.origin;
    }

    public Contract createRevision(PrivateKey... keys) {
        return createRevision(Do.list(keys));
    }

    public Contract createRevision(Collection<PrivateKey> keys) {
        Contract newRevision = createRevision();
        Set<KeyRecord> krs = new HashSet<>();
        keys.forEach(k -> {
            krs.add(new KeyRecord(k.getPublicKey()));
            newRevision.addSignerKey(k);
        });
        newRevision.setCreator(krs);
        newRevision.revokingItems.add(this);
        return newRevision;
    }


    public Role setCreator(Collection<KeyRecord> records) {
//        Role creator = new Role("creator", records);
//        setCreator(creator);
        return setRole("creator", records);
    }

    public Role setCreator(Role role) {
        return registerRole(role);
    }

    public Role getOwner() {
        return getRole("owner");
    }

    @NonNull
    public Role setOwnerKey(Object keyOrRecord) {
        return setRole("owner", Do.listOf(keyOrRecord));
    }

    @NonNull
    public Role setOwnerKeys(Collection<?> keys) {
        return setRole("owner", keys);
    }

    @NonNull
    public Role setOwnerKeys(PublicKey... keys) {
        return setOwnerKeys(asList(keys));
    }

    @NonNull
    private Role setRole(String name, Collection keys) {
        return registerRole(new SimpleRole(name, keys));
    }

    public Role getCreator() {
        return getRole("creator");
    }

    public Multimap<String, Permission> getPermissions() {
        return permissions;
    }

    public Binder getStateData() {
        return state.getData();
    }

    public Role setIssuerKeys(PublicKey... keys) {
        return setRole("issuer", asList(keys));
    }

    public void setExpiresAt(ZonedDateTime dateTime) {
        definition.setExpiresAt(dateTime);
    }

    @Override
    public void deserialize(Binder data, BiDeserializer deserializer) {
        int l = data.getIntOrThrow("api_level");
        if (l != apiLevel)
            throw new RuntimeException("contract api level conflict: found " + l + " my level "+apiLevel);
        deserializer.withContext(this, () -> {
            if( definition == null )
                definition = new Definition();
            definition.deserializeWith(data.getBinderOrThrow("definition"), deserializer);
            if( state == null )
                state = new State();
            state.deserealizeWith(data.getBinderOrThrow("state"), deserializer);
        });
//        throw new RuntimeException("not yet ready");
    }

    @Override
    public Binder serialize(BiSerializer s) {
        return Binder.of(
                "api_level", apiLevel,
                "definition", definition.serializeWith(s),
                "state", state.serializeWith(s)
        );
    }

    public class State {
        private int revision;
        private Binder state;
        private ZonedDateTime createdAt;
        //        private Role createdBy;
        private HashId origin;
        private HashId parent;
        private Binder data;

        private State() {
            createdAt = definition.createdAt;
            revision = 1;
        }

        private State initializeWithDsl(Binder state) {
            this.state = state;
            createdAt = state.getZonedDateTime("created_at", null);
            revision = state.getIntOrThrow("revision");
            data = state.getOrCreateBinder("data");
            if (createdAt == null) {
                if (revision != 1)
                    throw new IllegalArgumentException("state.created_at must be set for revisions > 1");
                createdAt = definition.createdAt;
            }
            createRole("owner", state.get("owner"));
            createRole("creator", state.getOrThrow("created_by"));
            return this;
        }

        public int getRevision() {
            return revision;
        }

        public ZonedDateTime getCreatedAt() {
            return createdAt;
        }

        public Binder serializeWith(BiSerializer serializer) {
            return serializer.serialize(
                    Binder.of(
                            "created_at", createdAt,
                            "revision", revision,
                            "owner", getRole("owner"),
                            "created_by", getRole("creator"),
                            "data", data
                    )
            );
        }

        public Binder getData() {
            return data;
        }

        public void deserealizeWith(Binder data, BiDeserializer d) {
            createdAt = data.getZonedDateTimeOrThrow("created_at");
            revision = data.getIntOrThrow("revision");
            if (revision <= 0)
                throw new IllegalArgumentException("illegal revision number: " + revision);
            Role r = registerRole(d.deserialize(data.getBinderOrThrow("owner")));
            if (!r.getName().equals("owner"))
                throw new IllegalArgumentException("bad owner role name");
            r = registerRole(d.deserialize(data.getBinderOrThrow("created_by")));
            if (!r.getName().equals("creator"))
                throw new IllegalArgumentException("bad creator role name");
            this.data = data.getBinderOrThrow("data");
        }
    }

    private Multimap<String, Permission> permissions = new Multimap<>();

    public class Definition {

        private ZonedDateTime createdAt;

        public void setExpiresAt(ZonedDateTime expiresAt) {
            this.expiresAt = expiresAt;
        }

        public void setData(Binder data) {
            this.data = data;
        }

        private ZonedDateTime expiresAt;
        private Binder definition;
        private Binder data;


        private Definition() {
            createdAt = ZonedDateTime.now();
        }

        private Definition initializeWithDsl(Binder definition) {
            this.definition = definition;
            Role issuer = createRole("issuer", definition.getOrThrow("issuer"));
            createdAt = definition.getZonedDateTimeOrThrow("created_at");
            expiresAt = definition.getZonedDateTimeOrThrow("expires_at");
            registerRole(issuer);
            data = definition.getBinder("data");
            return this;
        }

        /**
         * Collect all permissions and create links to roles or new roles as appropriate
         */
        private void scanDslPermissions() {
            definition.getBinderOrThrow("permissions").forEach((name, params) -> {
                // this cimplex logic is needed to process both yaml-imported structures
                // and regular serialized data in the same place
                if (params instanceof Object[])
                    for (Object x : (Object[]) params)
                        loadDslPermission(name, x);
                else if (params instanceof List)
                    for (Object x : (List) params)
                        loadDslPermission(name, x);
                else
                    loadDslPermission(name, params);
            });
        }

        private void loadDslPermission(String name, Object params) {
            String roleName = null;
            Role role = null;
            Binder binderParams = null;
            if (params instanceof CharSequence)
                // yaml style: permission: role
                roleName = params.toString();
            else {
                // extended yaml style or serialized object
                binderParams = Binder.from(params);
                Object x = binderParams.getOrThrow("role");
                if (x instanceof Role)
                    // serialized, role object
                    role = registerRole((Role) x);
                else
                    // yaml, extended form: permission: { role: name, ... }
                    roleName = x.toString();
            }
            if (role == null && roleName != null) {
                // we need to create alias to existing role
                role = createRole("@" + name, roleName);
            }
            if (role == null)
                throw new IllegalArgumentException("permission " + name + " refers to missing role: " + roleName);
            // now we have ready role and probably parameter for custom rights creation
            permissions.put(name, Permission.forName(name, role, params instanceof String ? null : binderParams));
        }

        public Binder getData() {
            if (data == null)
                data = new Binder();
            return data;
        }

        public Binder serializeWith(BiSerializer serializer) {
            return serializer.serialize(
                    Binder.of(
                            "issuer", getIssuer(),
                            "created_at", createdAt,
                            "expires_at", expiresAt,
                            "data", data,
                            "permissions", permissions.values()
                    )
            );
        }

        public void deserializeWith(Binder data, BiDeserializer d) {
            registerRole(d.deserialize(data.getBinderOrThrow("issuer")));
            createdAt = data.getZonedDateTimeOrThrow("created_at");
            expiresAt = data.getZonedDateTimeOrThrow("expires_at");
            this.data = d.deserialize(data.getBinderOrThrow("data"));
            List<Permission> perms = d.deserializeCollection(data.getOrThrow("permissions"));
            perms.forEach(perm -> permissions.put(perm.getName(), perm));
        }

    }

    public void traceErrors() {
        errors.forEach(e -> {
            System.out.println("Error: " + e);
        });
    }

    public String getErrorsString() {
        return errors.stream().map(Object::toString).collect(Collectors.joining(","));
    }

    static {
        DefaultBiMapper.registerClass(Contract.class);
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return copy();
    }

    /**
     * Make a valid deep copy of a contract
     *
     * @return
     */
    public Contract copy() {
        return DefaultBiMapper.deserialize(DefaultBiMapper.serialize(this));
    }
}
