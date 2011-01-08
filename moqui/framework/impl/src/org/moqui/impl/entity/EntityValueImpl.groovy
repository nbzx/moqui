/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.entity

import java.sql.Timestamp
import java.sql.Time
import java.sql.Date
import java.sql.ResultSet

import org.apache.commons.collections.set.ListOrderedSet

import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.entity.EntityException
import org.moqui.impl.entity.EntityQueryBuilder.EntityConditionParameter
import org.moqui.entity.EntityFind

import org.w3c.dom.Element
import org.w3c.dom.Document
import org.apache.commons.codec.binary.Base64
import org.moqui.impl.StupidUtilities
import org.moqui.Moqui
import org.moqui.impl.context.ExecutionContextFactoryImpl

class EntityValueImpl implements EntityValue {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EntityDefinition.class)

    /** This is a reference to where the entity value came from.
     * It is volatile so not stored when this is serialized, and will get a reference to the active EntityFacade after. 
     */
    protected volatile EntityFacadeImpl efi

    protected final String entityName
    protected volatile EntityDefinition entityDefinition

    protected final Map<String, Object> valueMap = new HashMap()
    /* Original DB Value Map: not used unless the value has been modified from its original state from the DB */
    protected Map<String, Object> dbValueMap = null

    protected boolean modified = false
    protected boolean mutable = true

    protected EntityValueImpl(EntityDefinition entityDefinition, EntityFacadeImpl efi) {
        this.efi = efi
        this.entityName = entityDefinition.getEntityName()
        this.entityDefinition = entityDefinition
    }

    EntityFacadeImpl getEntityFacadeImpl() {
        // handle null after deserialize; this requires a static reference in Moqui.java or we'll get an error
        if (!efi) efi = ((ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()).getEntityFacade()
        return efi
    }

    EntityDefinition getEntityDefinition() {
        if (!entityDefinition) entityDefinition = getEntityFacadeImpl().getEntityDefinition(entityName)
        return entityDefinition
    }

    void setSyncedWithDb() { dbValueMap = (Map) valueMap.clone(); modified = false }

    /** @see org.moqui.entity.EntityValue#getEntityName() */
    String getEntityName() { return entityName }

    /** @see org.moqui.entity.EntityValue#isModified() */
    boolean isModified() { return modified }

    /** @see org.moqui.entity.EntityValue#isMutable() */
    boolean isMutable() { return mutable }

    /** @see org.moqui.entity.EntityValue#get(String) */
    Object get(String name) {
        Node fieldNode = getEntityDefinition().getFieldNode(name)
        if (!fieldNode) {
            // if this is not a valid field name but is a valid relationship name, do a getRelated or getRelatedOne to return an EntityList or an EntityValue
            Node relationship = (Node) this.getEntityDefinition().entityNode.relationship.find({ it."@title" + it."@related-entity-name" == name })
            if (relationship) {
                if (relationship."@type" == "many") {
                    return this.findRelated(name, null, null, null, null)
                } else {
                    return this.findRelatedOne(name, null, null)
                }
            } else {
                throw new IllegalArgumentException("The name [${name}] is not a valid field name or relationship name for entity [${this.entityName}]")
            }
        }

        // if enabled use LocalizedEntityField for any localized fields
        if (fieldNode."@enable-localization" == "true") {
            Locale userLocale = getEntityFacadeImpl().ecfi.executionContext.user.locale
            if (userLocale) {
                EntityFind lefFind = getEntityFacadeImpl().makeFind("LocalizedEntityField")
                lefFind.condition([entityName:entityName, fieldName:name, locale:userLocale.toString()])
                EntityValue lefValue = lefFind.useCache(true).one()
                if (lefValue) return lefValue.localized
                // no luck? try getting a localized value from LocalizedMessage
                EntityFind lmFind = getEntityFacadeImpl().makeFind("LocalizedMessage")
                lmFind.condition([original:valueMap[name], locale:userLocale.toString()])
                EntityValue lmValue = lmFind.useCache(true).one()
                if (lmValue) return lmValue.localized
            }
        }

        return valueMap[name]
    }

    /** @see org.moqui.entity.EntityValue#containsPrimaryKey() */
    boolean containsPrimaryKey() {
        for (String fieldName in this.getEntityDefinition().getFieldNames(true, false)) {
            if (!valueMap[fieldName]) return false
        }
        return true
    }

    /** @see org.moqui.entity.EntityValue#set(String, Object) */
    void set(String name, Object value) {
        if (!mutable) throw new IllegalArgumentException("Cannot set field [${name}], this entity value is not mutable (it is read-only)")
        if (!getEntityDefinition().isField(name)) {
            throw new IllegalArgumentException("The name [${name}] is not a valid field name for entity [${entityName}]")
        }
        if (valueMap[name] != value) modified = true
        valueMap.put(name, value)
    }

    /** @see org.moqui.entity.EntityValue#setString(String, String) */
    void setString(String name, String value) {
        entityDefinition.setString(name, value, this)
    }

    /** @see org.moqui.entity.EntityValue#getBoolean(String) */
    Boolean getBoolean(String name) { return this.get(name) as Boolean }

    /** @see org.moqui.entity.EntityValue#getString(String) */
    String getString(String name) {
        Object valueObj = this.get(name)
        return valueObj ? valueObj.toString() : null
    }

    /** @see org.moqui.entity.EntityValue#getTimestamp(String) */
    Timestamp getTimestamp(String name) {
        // NOTE: all of these methods are using the Groovy asType to do type conversion
        // if any don't work use the org.apache.commons.beanutils.Converter stuff
        return (Timestamp) this.get(name).asType(Timestamp.class)
    }

    /** @see org.moqui.entity.EntityValue#getTime(String) */
    Time getTime(String name) { return this.get(name) as Time }

    /** @see org.moqui.entity.EntityValue#getDate(String) */
    Date getDate(String name) { return this.get(name) as Date }

    /** @see org.moqui.entity.EntityValue#getLong(String) */
    Long getLong(String name) { return this.get(name) as Long }

    /** @see org.moqui.entity.EntityValue#getDouble(String) */
    Double getDouble(String name) { return this.get(name) as Double }

    /** @see org.moqui.entity.EntityValue#getBigDecimal(String) */
    BigDecimal getBigDecimal(String name) { return this.get(name) as BigDecimal }

    /** @see org.moqui.entity.EntityValue#setFields(Map, boolean, java.lang.String, boolean) */
    void setFields(Map<String, ?> fields, boolean setIfEmpty, String namePrefix, Boolean pks) {
        entityDefinition.setFields(fields, this, setIfEmpty, namePrefix, pks)
    }

    /** @see org.moqui.entity.EntityValue#compareTo(EntityValue) */
    int compareTo(EntityValue that) {
        // nulls go earlier
        if (that == null) return -1

        // first entity names
        int result = this.entityName.compareTo(that.getEntityName())
        if (result != 0) return result

        // next compare PK fields
        for (String pkFieldName in this.getEntityDefinition().getFieldNames(true, false)) {
            result = compareFields(that, pkFieldName)
            if (result != 0) return result
        }
        // then non-PK fields
        for (String fieldName in this.getEntityDefinition().getFieldNames(false, true)) {
            result = compareFields(that, fieldName)
            if (result != 0) return result
        }

        // all the same, result should be 0
        return result
    }

    protected int compareFields(EntityValue that, String name) {
        Comparable thisVal = (Comparable) this.valueMap.get(name)
        Object thatVal = that.get(name)
        // NOTE: nulls go earlier in the list
        if (thisVal == null) {
            return thatVal == null ? 0 : 1
        } else {
            return thatVal == null ? -1 : thisVal.compareTo(thatVal)
        }
    }

    /** @see org.moqui.entity.EntityValue#create() */
    void create() {
        // TODO: add EECA execution
        if (getEntityDefinition().isViewEntity()) {
            throw new IllegalArgumentException("Create not yet implemented for view-entity")
        } else {
            if (getEntityDefinition().isField("lastUpdatedStamp") && !this.get("lastUpdatedStamp"))
                this.set("lastUpdatedStamp", new Timestamp(System.currentTimeMillis()))

            ListOrderedSet allFieldList = getEntityDefinition().getFieldNames(true, true)
            ListOrderedSet fieldList = new ListOrderedSet()
            for (String fieldName in allFieldList) if (valueMap.containsKey(fieldName)) fieldList.add(fieldName)

            EntityQueryBuilder eqb = new EntityQueryBuilder(getEntityDefinition(), getEntityFacadeImpl())
            StringBuilder sql = eqb.getSqlTopLevel()
            sql.append("INSERT INTO ").append(getEntityDefinition().getTableName())

            sql.append(" (")
            boolean isFirstField = true
            StringBuilder values = new StringBuilder()
            for (String fieldName in fieldList) {
                if (isFirstField) {
                    isFirstField = false
                } else {
                    sql.append(", ")
                    values.append(", ")
                }
                sql.append(getEntityDefinition().getColumnName(fieldName, false))
                values.append('?')
            }
            sql.append(") VALUES (").append(values.toString()).append(')')

            try {
                eqb.makeConnection()
                eqb.makePreparedStatement()
                int index = 1
                for (String fieldName in fieldList) {
                    eqb.setPreparedStatementValue(index, valueMap.get(fieldName), getEntityDefinition().getFieldNode(fieldName))
                    index++
                }
                eqb.executeUpdate()
                setSyncedWithDb()
                // TODO: notify cache clear
            } catch (EntityException e) {
                throw new EntityException("Error in create of [${this.toString()}]", e)
            } finally {
                eqb.closeAll()
            }
        }
    }

    /** @see org.moqui.entity.EntityValue#createOrUpdate() */
    void createOrUpdate() {
        EntityValue dbValue = (EntityValue) this.clone()
        if (dbValue.refresh()) {
            create()
        } else {
            update()
        }
    }

    /** @see org.moqui.entity.EntityValue#update() */
    void update() {
        // TODO: add EECA execution
        if (getEntityDefinition().isViewEntity()) {
            throw new IllegalArgumentException("Update not yet implemented for view-entity")
        } else {
            ListOrderedSet pkFieldList = getEntityDefinition().getFieldNames(true, false)

            ListOrderedSet nonPkAllFieldList = getEntityDefinition().getFieldNames(false, true)
            ListOrderedSet nonPkFieldList = new ListOrderedSet()
            for (String fieldName in nonPkAllFieldList) {
                if (valueMap.containsKey(fieldName) &&
                        (!dbValueMap || valueMap.get(fieldName) != dbValueMap.get(fieldName))) {
                    nonPkFieldList.add(fieldName)
                }
            }
            if (!nonPkFieldList) {
                logger.trace("Not doing update on entity with no populated non-PK fields; entity=" + this.toString())
                return
            }

            if (getEntityDefinition().getEntityNode()."@optimistic-lock" == "true") {
                EntityValue dbValue = (EntityValue) this.clone()
                dbValue.refresh()
                if (getTimestamp("lastUpdatedStamp") != dbValue.getTimestamp("lastUpdatedStamp"))
                    throw new EntityException("This record was updated by someone else at [${getTimestamp("lastUpdatedStamp")}] which was after the version you loaded at [${dbValue.getTimestamp("lastUpdatedStamp")}]. Not updating to avoid overwriting data.")
            }

            if (getEntityDefinition().isField("lastUpdatedStamp") && !this.get("lastUpdatedStamp"))
                this.set("lastUpdatedStamp", new Timestamp(System.currentTimeMillis()))

            EntityQueryBuilder eqb = new EntityQueryBuilder(getEntityDefinition(), getEntityFacadeImpl())
            StringBuilder sql = eqb.getSqlTopLevel()
            sql.append("UPDATE ").append(getEntityDefinition().getTableName()).append(" SET ")

            boolean isFirstField = true
            for (String fieldName in nonPkFieldList) {
                if (isFirstField) isFirstField = false else sql.append(", ")
                sql.append(getEntityDefinition().getColumnName(fieldName, false)).append("=?")
                eqb.getParameters().add(new EntityConditionParameter(getEntityDefinition().getFieldNode(fieldName),
                        valueMap.get(fieldName), eqb))
            }
            sql.append(" WHERE ")
            boolean isFirstPk = true
            for (String fieldName in pkFieldList) {
                if (isFirstPk) isFirstPk = false else sql.append(" AND ")
                sql.append(getEntityDefinition().getColumnName(fieldName, false)).append("=?")
                eqb.getParameters().add(new EntityConditionParameter(getEntityDefinition().getFieldNode(fieldName),
                        valueMap.get(fieldName), eqb))
            }

            try {
                eqb.makeConnection()
                eqb.makePreparedStatement()
                eqb.setPreparedStatementValues()
                if (eqb.executeUpdate() == 0)
                    throw new EntityException("Tried to update a value that does not exist [${this.toString()}].")
                setSyncedWithDb()
                // TODO: notify cache clear
            } catch (EntityException e) {
                throw new EntityException("Error in update of [${this.toString()}]", e)
            } finally {
                eqb.closeAll()
            }
        }
    }

    /** @see org.moqui.entity.EntityValue#delete() */
    void delete() {
        // TODO: add EECA execution
        if (getEntityDefinition().isViewEntity()) {
            throw new IllegalArgumentException("Delete not implemented for view-entity")
        } else {
            ListOrderedSet pkFieldList = getEntityDefinition().getFieldNames(true, false)

            EntityQueryBuilder eqb = new EntityQueryBuilder(getEntityDefinition(), getEntityFacadeImpl())
            StringBuilder sql = eqb.getSqlTopLevel()
            sql.append("DELETE FROM ").append(getEntityDefinition().getTableName()).append(" WHERE ")

            boolean isFirstPk = true
            for (String fieldName in pkFieldList) {
                if (isFirstPk) isFirstPk = false else sql.append(" AND ")
                sql.append(getEntityDefinition().getColumnName(fieldName, false)).append("=?")
                eqb.getParameters().add(new EntityConditionParameter(getEntityDefinition().getFieldNode(fieldName),
                        valueMap.get(fieldName), eqb))
            }

            try {
                eqb.makeConnection()
                eqb.makePreparedStatement()
                eqb.setPreparedStatementValues()
                if (eqb.executeUpdate() == 0) logger.info("Tried to delete a value that does not exist [${this.toString()}]")
                // TODO: notify cache clear
            } catch (EntityException e) {
                throw new EntityException("Error in delete of [${this.toString()}]", e)
            } finally {
                eqb.closeAll()
            }
        }
    }

    /** @see org.moqui.entity.EntityValue#refresh() */
    boolean refresh() {
        // NOTE: this simple approach may not work for view-entities, but not restricting for now

        ListOrderedSet pkFieldList = getEntityDefinition().getFieldNames(true, false)
        ListOrderedSet nonPkFieldList = getEntityDefinition().getFieldNames(false, true)
        if (!nonPkFieldList) {
            logger.trace("Not doing refresh on entity with no non-PK fields; entity=" + this.toString())
            return
        }

        EntityQueryBuilder eqb = new EntityQueryBuilder(getEntityDefinition(), getEntityFacadeImpl())
        StringBuilder sql = eqb.getSqlTopLevel()
        sql.append("SELECT ")
        boolean isFirstField = true
        for (String fieldName in nonPkFieldList) {
            if (isFirstField) isFirstField = false else sql.append(", ")
            sql.append(getEntityDefinition().getColumnName(fieldName, false))
        }

        sql.append(" FROM ").append(getEntityDefinition().getTableName()).append(" WHERE ")

        boolean isFirstPk = true
        for (String fieldName in pkFieldList) {
            if (isFirstPk) isFirstPk = false else sql.append(" AND ")
            sql.append(getEntityDefinition().getColumnName(fieldName, false)).append("=?")
            eqb.getParameters().add(new EntityConditionParameter(getEntityDefinition().getFieldNode(fieldName),
                    valueMap.get(fieldName), eqb))
        }

        boolean retVal = false
        try {
            eqb.makeConnection()
            eqb.makePreparedStatement()
            eqb.setPreparedStatementValues()

            ResultSet rs = eqb.executeQuery()
            if (rs.next()) {
                int j = 1
                for (String fieldName in nonPkFieldList) {
                    EntityQueryBuilder.getResultSetValue(rs, j, getEntityDefinition().getFieldNode(fieldName), this, getEntityFacadeImpl())
                    j++
                }
                retVal = true
                setSyncedWithDb()
            } else {
                logger.trace("No record found in refresh for entity [${entityName}] with values [${valueMap}]")
            }
        } catch (EntityException e) {
            throw new EntityException("Error in refresh of [${this.toString()}]", e)
        } finally {
            eqb.closeAll()
        }
        return retVal
    }

    /** @see org.moqui.entity.EntityValue#getOriginalDbValue(String) */
    Object getOriginalDbValue(String name) {
        return (dbValueMap && dbValueMap[name]) ? dbValueMap[name] : valueMap[name]
    }

    /** @see org.moqui.entity.EntityValue#findRelated(String, Map, java.util.List<java.lang.String>, Boolean, Boolean) */
    EntityList findRelated(String relationshipName, Map<String, ?> byAndFields, List<String> orderBy, Boolean useCache, Boolean forUpdate) {
        Node relationship = getEntityDefinition().getRelationshipNode(relationshipName)
        if (!relationship) throw new IllegalArgumentException("Relationship [${relationshipName}] not found in entity [${entityName}]")

        Map keyMap = getEntityDefinition().getRelationshipExpandedKeyMap(relationship)
        if (!keyMap) throw new IllegalArgumentException("Relationship [${relationshipName}] in entity [${entityName}] has no key-map sub-elements and no default values")

        // make a Map where the key is the related entity's field name, and the value is the value from this entity
        Map condMap = new HashMap()
        for (Map.Entry entry in keyMap.entrySet()) condMap.put(entry.getValue(), valueMap.get(entry.getKey()))
        if (byAndFields) condMap.putAll(byAndFields)

        EntityFind find = getEntityFacadeImpl().makeFind(relationship."@related-entity-name")
        return find.condition(condMap).orderBy(orderBy).useCache(useCache).forUpdate(forUpdate).list()
    }

    /** @see org.moqui.entity.EntityValue#findRelatedOne(String, Boolean, Boolean) */
    EntityValue findRelatedOne(String relationshipName, Boolean useCache, Boolean forUpdate) {
        Node relationship = getEntityDefinition().getRelationshipNode(relationshipName)
        if (!relationship) throw new IllegalArgumentException("Relationship [${relationshipName}] not found in entity [${entityName}]")
        return findRelatedOne(relationship, useCache, forUpdate)
    }

    protected EntityValue findRelatedOne(Node relationship, Boolean useCache, Boolean forUpdate) {
        Map keyMap = getEntityDefinition().getRelationshipExpandedKeyMap(relationship)
        if (!keyMap) throw new IllegalArgumentException("Relationship [${relationship."@title"}${relationship."@related-entity-name"}] in entity [${entityName}] has no key-map sub-elements and no default values")

        // make a Map where the key is the related entity's field name, and the value is the value from this entity
        Map condMap = new HashMap()
        for (Map.Entry entry in keyMap.entrySet()) condMap.put(entry.getValue(), valueMap.get(entry.getKey()))

        EntityFind find = getEntityFacadeImpl().makeFind(relationship."@related-entity-name")
        return find.condition(condMap).useCache(useCache).forUpdate(forUpdate).one()
    }

    /** @see org.moqui.entity.EntityValue#deleteRelated(String) */
    void deleteRelated(String relationshipName) {
        // NOTE: this does a select for update, may consider not doing that by default
        EntityList relatedList = findRelated(relationshipName, null, null, false, true)
        for (EntityValue relatedValue in relatedList) relatedValue.delete()
    }

    /** @see org.moqui.entity.EntityValue#checkFks(boolean) */
    boolean checkFks(boolean insertDummy) {
        for (Node oneRel in getEntityDefinition().entityNode."relationship".find({ it."@type" == "one" })) {
            EntityValue value = findRelatedOne(oneRel, true, false)
            if (!value) {
                if (insertDummy) {
                    EntityValue newValue = getEntityFacadeImpl().makeValue((String) oneRel."@related-entity-name")
                    Map keyMap = getEntityDefinition().getRelationshipExpandedKeyMap(oneRel)
                    if (!keyMap) throw new IllegalArgumentException("Relationship [${${oneRel."@title"}${oneRel."@related-entity-name"}}] in entity [${entityName}] has no key-map sub-elements and no default values")

                    // make a Map where the key is the related entity's field name, and the value is the value from this entity
                    for (Map.Entry entry in keyMap.entrySet())
                        newValue.set((String) entry.getValue(), valueMap.get(entry.getKey()))

                    if (newValue.containsPrimaryKey()){
                        newValue.create()
                    }
                } else {
                    return false
                }
            }
        }
        // if we haven't found one missing, we're all good
        return true
    }

    /** @see org.moqui.entity.EntityValue#makeXmlElement(Document, String) */
    Element makeXmlElement(Document document, String prefix) {
        Element element = null
        if (document != null) element = document.createElement((prefix ? prefix : "") + entityName)
        if (!element) return null

        for (String fieldName in getEntityDefinition().getFieldNames(true, true)) {
            String value = getString(fieldName)
            if (value) {
                if (value.contains('\n') || value.contains('\r')) {
                    Element childElement = document.createElement(fieldName)
                    element.appendChild(childElement)
                    childElement.appendChild(document.createCDATASection(value))
                } else {
                    element.setAttribute(fieldName, value)
                }
            }
        }

        return element
    }

    /** @see org.moqui.entity.EntityValue#writeXmlText(PrintWriter, String) */
    void writeXmlText(PrintWriter pw, String prefix) {
        // indent 4 spaces
        String indentString = "    "
        // if a CDATA element is needed for a field it goes in this Map to be added at the end
        Map<String, String> cdataMap = new HashMap()

        pw.print(indentString); pw.print('<'); if (prefix) pw.print(prefix); pw.print(entityName);

        for (String fieldName in getEntityDefinition().getFieldNames(true, true)) {
            Node fieldNode = getEntityDefinition().getFieldNode(fieldName)
            String type = fieldNode."@type"

            if (type == "binary-very-long") {
                Object obj = get(fieldName)
                if (obj instanceof byte[]) {
                    cdataMap.put(fieldName, new String(Base64.encodeBase64((byte[]) obj)))
                } else {
                    logger.warn("Field [${fieldName}] on entity [${entityName}] is not of type 'byte[]', is [${obj}] so skipping, won't be in export")
                }
                continue
            }

            String valueStr = getString(fieldName)
            if (!valueStr) continue
            if (valueStr.contains('\n') || valueStr.contains('\r')) {
                cdataMap.put(fieldName, valueStr)
                continue
            }

            pw.print(' '); pw.print(fieldName); pw.print("=\"");
            pw.print(StupidUtilities.encodeForXmlAttribute(valueStr))
            pw.print("\"")
        }

        if (cdataMap.size() == 0) {
            // self-close the entity element
            pw.println("/>")
        } else {
            pw.println('>')

            for (Map.Entry<String, String> entry in cdataMap.entrySet()) {
                pw.print(indentString); pw.print(indentString);
                pw.print('<'); pw.print(entry.getKey()); pw.print('>');
                pw.print("<![CDATA["); pw.print(entry.getValue()); pw.print("]]>");
                pw.print("</"); pw.print(entry.getKey()); pw.println('>');
            }

            // close the entity element
            pw.print(indentString); pw.print("</"); pw.print(entityName); pw.println(">");
        }
    }

    // ========== Map Interface Methods ==========

    /** @see java.util.Map#size() */
    int size() { return valueMap.size() }

    boolean isEmpty() { return valueMap.isEmpty() }

    boolean containsKey(Object o) { return valueMap.containsKey(o) }

    boolean containsValue(Object o) { return valueMap.containsValue(o) }

    Object get(Object o) {
        if (o instanceof String) {
            // This may throw an exception, and let it; the Map interface doesn't provide for IllegalArgumentException
            //   but it is far more useful than a log message that is likely to be ignored.
            return this.get((String) o)
        } else {
            return null
        }
    }

    Object put(String k, Object v) {
        Object original = this.get(k)
        this.set(k, v)
        return original
    }

    Object remove(Object o) {
        if (valueMap.containsKey(o)) modified = true
        return valueMap.remove(o)
    }

    void putAll(Map<? extends String, ? extends Object> map) {
        for(Map.Entry entry in map.entrySet()) {
            this.set((String) entry.key, entry.value)
        }
    }

    void clear() { valueMap.clear() }

    Set<String> keySet() { return Collections.unmodifiableSet(valueMap.keySet()) }

    Collection<Object> values() { return Collections.unmodifiableCollection(valueMap.values()) }

    Set<Map.Entry<String, Object>> entrySet() { return Collections.unmodifiableSet(valueMap.entrySet()) }

    // ========== Object Override Methods ==========

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != this.getClass()) return false
        // reuse the compare method
        return this.compareTo((EntityValue) obj) == 0
    }

    @Override
    public int hashCode() {
        // NOTE: consider caching the hash code in the future for performance
        return entityName.hashCode() + valueMap.hashCode()
    }

    @Override
    String toString() {
        // general SQL where clause style text with values included
        return "[${entityName}: ${valueMap}]"
    }

    @Override
    public Object clone() {
        return this.cloneValue()
    }

    public EntityValue cloneValue() {
        EntityValueImpl newObj = new EntityValueImpl(getEntityDefinition(), entityFacadeImpl)
        newObj.valueMap.putAll(valueMap)
        if (dbValueMap) newObj.dbValueMap = dbValueMap.clone()
        // don't set mutable (default to mutable even if original was not) or modified (start out not modified)
        return newObj
    }
}